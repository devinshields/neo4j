/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.neo4j.graphdb.Direction;
import org.neo4j.helpers.Predicate;
import org.neo4j.helpers.PrimitiveLongPredicate;
import org.neo4j.helpers.ThisShouldNotHappenError;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.TxState;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.RelationshipTypeIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.TransactionalException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintVerificationFailedKernelException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.IllegalTokenNameException;
import org.neo4j.kernel.api.exceptions.schema.IndexBrokenKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.api.properties.PropertyKeyIdIterator;
import org.neo4j.kernel.impl.api.operations.EntityOperations;
import org.neo4j.kernel.impl.api.operations.KeyReadOperations;
import org.neo4j.kernel.impl.api.operations.KeyWriteOperations;
import org.neo4j.kernel.impl.api.operations.SchemaReadOperations;
import org.neo4j.kernel.impl.api.operations.SchemaWriteOperations;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.core.Token;
import org.neo4j.kernel.impl.nioneo.store.SchemaStorage;
import org.neo4j.kernel.impl.util.DiffSets;
import org.neo4j.kernel.impl.util.PrimitiveIntIterator;
import org.neo4j.kernel.impl.util.PrimitiveLongIterator;

import static java.util.Collections.emptyList;
import static org.neo4j.helpers.collection.Iterables.filter;
import static org.neo4j.helpers.collection.Iterables.option;
import static org.neo4j.helpers.collection.IteratorUtil.emptyPrimitiveIntIterator;
import static org.neo4j.helpers.collection.IteratorUtil.emptyPrimitiveLongIterator;
import static org.neo4j.helpers.collection.IteratorUtil.filter;
import static org.neo4j.helpers.collection.IteratorUtil.single;
import static org.neo4j.helpers.collection.IteratorUtil.singleOrNull;
import static org.neo4j.helpers.collection.IteratorUtil.toPrimitiveIntIterator;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE;

public class StateHandlingStatementOperations implements
        KeyReadOperations,
        KeyWriteOperations,
        EntityOperations,
        SchemaReadOperations,
        SchemaWriteOperations
{
    private final StoreReadLayer storeLayer;
    private final LegacyPropertyTrackers legacyPropertyTrackers;
    private final ConstraintIndexCreator constraintIndexCreator;

    public StateHandlingStatementOperations(
            StoreReadLayer storeLayer, LegacyPropertyTrackers propertyTrackers,
            ConstraintIndexCreator constraintIndexCreator )
    {
        this.storeLayer = storeLayer;
        this.legacyPropertyTrackers = propertyTrackers;
        this.constraintIndexCreator = constraintIndexCreator;
    }

    @Override
    public long nodeCreate( KernelStatement statement )
    {
        return statement.txState().nodeDoCreate();
    }

    @Override
    public void nodeDelete( KernelStatement state, long nodeId )
    {
        legacyPropertyTrackers.nodeDelete( nodeId );
        state.txState().nodeDoDelete( nodeId );
    }

    @Override
    public long relationshipCreate( KernelStatement state, int relationshipTypeId, long startNodeId, long endNodeId )
    {
        long id = state.txState().relationshipDoCreate( relationshipTypeId, startNodeId, endNodeId );
        state.neoStoreTransaction.relationshipCreate( id, relationshipTypeId, startNodeId, endNodeId );
        return id;
    }

    @Override
    public void relationshipDelete( KernelStatement state, long relationshipId )
    {
        // NOTE: We implicitly delegate to neoStoreTransaction via txState.legacyState here. This is because that
        // call returns modified properties, which node manager uses to update legacy tx state. This will be cleaned up
        // once we've removed legacy tx state.
        legacyPropertyTrackers.relationshipDelete( relationshipId );
        final TxState txState = state.txState();
        if(txState.relationshipIsAddedInThisTx( relationshipId ))
        {
            txState.relationshipDoDeleteAddedInThisTx( relationshipId );
        }
        else
        {
            try
            {
                storeLayer.visit( relationshipId, new StoreReadLayer.RelationshipVisitor()
                {
                    @Override
                    public void visit( long relId, long startNode, long endNode, int type )
                    {
                        txState.relationshipDoDelete( relId, startNode, endNode, type );
                    }
                });
            }
            catch ( EntityNotFoundException e )
            {
                // If it doesn't exist, it doesn't exist, and the user got what she wanted.
                return;
            }
        }
    }

    @Override
    public boolean nodeHasLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            if ( state.txState().nodeIsDeletedInThisTx( nodeId ) )
            {
                return false;
            }

            if ( state.txState().nodeIsAddedInThisTx( nodeId ) )
            {
                TxState.UpdateTriState labelState = state.txState().labelState( nodeId, labelId );
                return labelState.isTouched() && labelState.isAdded();
            }

            TxState.UpdateTriState labelState = state.txState().labelState( nodeId, labelId );
            if ( labelState.isTouched() )
            {
                return labelState.isAdded();
            }
        }

        return storeLayer.nodeHasLabel( state, nodeId, labelId );
    }

    @Override
    public PrimitiveIntIterator nodeGetLabels( KernelStatement state, long nodeId ) throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            if ( state.txState().nodeIsDeletedInThisTx( nodeId ) )
            {
                return IteratorUtil.emptyPrimitiveIntIterator();
            }

            if ( state.txState().nodeIsAddedInThisTx( nodeId ) )
            {
                return toPrimitiveIntIterator(
                        state.txState().nodeStateLabelDiffSets( nodeId ).getAdded().iterator() );
            }

            return state.txState().nodeStateLabelDiffSets( nodeId ).augment(
                    storeLayer.nodeGetLabels( nodeId ) );
        }

        return storeLayer.nodeGetLabels( nodeId );
    }

    @Override
    public PrimitiveIntIterator nodeGetCommittedLabels( KernelStatement state, long nodeId ) throws EntityNotFoundException
    {
        if( state.hasTxStateWithChanges() && state.txState().nodeIsAddedInThisTx( nodeId ))
        {
            return emptyPrimitiveIntIterator();
        }
        return storeLayer.nodeGetLabels(nodeId);
    }

    @Override
    public boolean nodeAddLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        if ( nodeHasLabel( state, nodeId, labelId ) )
        {
            // Label is already in state or in store, no-op
            return false;
        }

        state.txState().nodeDoAddLabel( labelId, nodeId );
        return true;
    }

    @Override
    public boolean nodeRemoveLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        if ( !nodeHasLabel( state, nodeId, labelId ) )
        {
            // Label does not exist in state nor in store, no-op
            return false;
        }

        state.txState().nodeDoRemoveLabel( labelId, nodeId );

        return true;
    }

    @Override
    public PrimitiveLongIterator nodesGetForLabel( KernelStatement state, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            PrimitiveLongIterator wLabelChanges =
                    state.txState().nodesWithLabelChanged( labelId ).augment(
                            storeLayer.nodesGetForLabel( state, labelId ) );
            return state.txState().addedAndRemovedNodes().augmentWithRemovals( wLabelChanges );
        }

        return storeLayer.nodesGetForLabel( state, labelId );
    }

    @Override
    public IndexDescriptor indexCreate( KernelStatement state, int labelId, int propertyKey )
    {
        IndexDescriptor rule = new IndexDescriptor( labelId, propertyKey );
        state.txState().indexRuleDoAdd( rule );
        return rule;
    }

    @Override
    public void indexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        state.txState().indexDoDrop( descriptor );
    }

    @Override
    public void uniqueIndexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        state.txState().constraintIndexDoDrop( descriptor );
    }

    @Override
    public UniquenessConstraint uniquenessConstraintCreate( KernelStatement state, int labelId, int propertyKeyId )
            throws CreateConstraintFailureException
    {
        UniquenessConstraint constraint = new UniquenessConstraint( labelId, propertyKeyId );
        try
        {
            IndexDescriptor index = new IndexDescriptor( labelId, propertyKeyId );
            if ( state.txState().constraintIndexDoUnRemove( index ) ) // ..., DROP, *CREATE*
            { // creation is undoing a drop
                state.txState().constraintIndexDiffSetsByLabel( labelId ).unRemove( index );
                if ( !state.txState().constraintDoUnRemove( constraint ) ) // CREATE, ..., DROP, *CREATE*
                { // ... the drop we are undoing did itself undo a prior create...
                    state.txState().constraintsChangesForLabel( labelId ).unRemove( constraint );
                    state.txState().constraintDoAdd(
                            constraint, state.txState().indexCreatedForConstraint( constraint ) );
                }
            }
            else // *CREATE*
            { // create from scratch
                for ( Iterator<UniquenessConstraint> it = storeLayer.constraintsGetForLabelAndPropertyKey(
                        labelId, propertyKeyId ); it.hasNext(); )
                {
                    if ( it.next().equals( labelId, propertyKeyId ) )
                    {
                        return constraint;
                    }
                }
                long indexId = constraintIndexCreator.createUniquenessConstraintIndex(
                        state, this, labelId, propertyKeyId );
                state.txState().constraintDoAdd( constraint, indexId );
            }
            return constraint;
        }
        catch ( TransactionalException | ConstraintVerificationFailedKernelException | DropIndexFailureException e )
        {
            throw new CreateConstraintFailureException( constraint, e );
        }
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetForLabelAndPropertyKey( KernelStatement state,
            int labelId, int propertyKeyId )
    {
        return applyConstraintsDiff( state, storeLayer.constraintsGetForLabelAndPropertyKey(
                labelId, propertyKeyId ), labelId, propertyKeyId );
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetForLabel( KernelStatement state, int labelId )
    {
        return applyConstraintsDiff( state, storeLayer.constraintsGetForLabel( labelId ), labelId );
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetAll( KernelStatement state )
    {
        return applyConstraintsDiff( state, storeLayer.constraintsGetAll() );
    }

    private Iterator<UniquenessConstraint> applyConstraintsDiff( KernelStatement state,
            Iterator<UniquenessConstraint> constraints, int labelId, int propertyKeyId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            DiffSets<UniquenessConstraint> diff =
                    state.txState().constraintsChangesForLabelAndProperty( labelId, propertyKeyId );
            if ( diff != null )
            {
                return diff.apply( constraints );
            }
        }

        return constraints;
    }

    private Iterator<UniquenessConstraint> applyConstraintsDiff( KernelStatement state,
            Iterator<UniquenessConstraint> constraints, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            DiffSets<UniquenessConstraint> diff = state.txState().constraintsChangesForLabel( labelId );
            if ( diff != null )
            {
                return diff.apply( constraints );
            }
        }

        return constraints;
    }

    private Iterator<UniquenessConstraint> applyConstraintsDiff( KernelStatement state,
            Iterator<UniquenessConstraint> constraints )
    {
        if ( state.hasTxStateWithChanges() )
        {
            DiffSets<UniquenessConstraint> diff = state.txState().constraintsChanges();
            if ( diff != null )
            {
                return diff.apply( constraints );
            }
        }

        return constraints;
    }

    @Override
    public void constraintDrop( KernelStatement state, UniquenessConstraint constraint )
    {
        state.txState().constraintDoDrop( constraint );
    }

    @Override
    public IndexDescriptor indexesGetForLabelAndPropertyKey( KernelStatement state, int labelId, int propertyKey )
            throws SchemaRuleNotFoundException
    {
        Iterable<IndexDescriptor> committedRules;
        try
        {
            committedRules = option( storeLayer.indexesGetForLabelAndPropertyKey( labelId,
                    propertyKey ) );
        }
        catch ( SchemaRuleNotFoundException e )
        {
            committedRules = emptyList();
        }
        DiffSets<IndexDescriptor> ruleDiffSet = state.txState().indexDiffSetsByLabel( labelId );

        Iterator<IndexDescriptor> rules =
                state.hasTxStateWithChanges() ? ruleDiffSet.apply( committedRules.iterator() ) : committedRules
                        .iterator();
        IndexDescriptor single = singleOrNull( rules );
        if ( single == null )
        {
            throw new SchemaRuleNotFoundException( "Index rule for label:" + labelId + " and property:" +
                    propertyKey + " not found" );
        }
        return single;
    }

    @Override
    public InternalIndexState indexGetState( KernelStatement state, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        // If index is in our state, then return populating
        if ( state.hasTxStateWithChanges() )
        {
            if ( checkIndexState( descriptor, state.txState().indexDiffSetsByLabel( descriptor.getLabelId() ) ) )
            {
                return InternalIndexState.POPULATING;
            }
            if ( checkIndexState( descriptor, state.txState().constraintIndexDiffSetsByLabel( descriptor.getLabelId()
            ) ) )
            {
                return InternalIndexState.POPULATING;
            }
        }

        return storeLayer.indexGetState( descriptor );
    }

    private boolean checkIndexState( IndexDescriptor indexRule, DiffSets<IndexDescriptor> diffSet )
            throws IndexNotFoundKernelException
    {
        if ( diffSet.isAdded( indexRule ) )
        {
            return true;
        }
        if ( diffSet.isRemoved( indexRule ) )
        {
            throw new IndexNotFoundKernelException( String.format( "Index for label id %d on property id %d has been " +
                    "dropped in this transaction.",
                    indexRule.getLabelId(),
                    indexRule.getPropertyKeyId() ) );
        }
        return false;
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( KernelStatement state, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().indexDiffSetsByLabel( labelId )
                    .apply( storeLayer.indexesGetForLabel( labelId ) );
        }

        return storeLayer.indexesGetForLabel( labelId );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().indexChanges().apply( storeLayer.indexesGetAll() );
        }

        return storeLayer.indexesGetAll();
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetForLabel( KernelStatement state, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintIndexDiffSetsByLabel( labelId )
                    .apply( storeLayer.uniqueIndexesGetForLabel( labelId ) );
        }

        return storeLayer.uniqueIndexesGetForLabel( labelId );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetAll( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintIndexChanges()
                    .apply( storeLayer.uniqueIndexesGetAll() );
        }

        return storeLayer.uniqueIndexesGetAll();
    }

    @Override
    public long nodeGetUniqueFromIndexLookup(
            KernelStatement state,
            IndexDescriptor index,
            Object value )
            throws IndexNotFoundKernelException, IndexBrokenKernelException
    {
        PrimitiveLongIterator committed = storeLayer.nodeGetUniqueFromIndexLookup( state, index, value );
        PrimitiveLongIterator exactMatches = filterExactIndexMatches( state, index, value, committed );
        PrimitiveLongIterator changeFilteredMatches = filterIndexStateChanges( state, index, value, exactMatches );
        return single( changeFilteredMatches, NO_SUCH_NODE );
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexLookup( KernelStatement state, IndexDescriptor index, final Object value )
            throws IndexNotFoundKernelException
    {
        PrimitiveLongIterator committed = storeLayer.nodesGetFromIndexLookup( state, index, value );
        PrimitiveLongIterator exactMatches = filterExactIndexMatches( state, index, value, committed );
        PrimitiveLongIterator changeFilteredMatches = filterIndexStateChanges( state, index, value, exactMatches );
        return changeFilteredMatches;
    }

    private PrimitiveLongIterator filterExactIndexMatches(
            KernelStatement state,
            IndexDescriptor index,
            Object value,
            PrimitiveLongIterator committed )
    {
        if ( isNumberOrArray( value ) )
        {
            return filter( exactMatch( state, index.getPropertyKeyId(), value ), committed );
        }
        return committed;
    }

    private boolean isNumberOrArray( Object value )
    {
        return value instanceof Number || value.getClass().isArray();
    }

    private PrimitiveLongPredicate exactMatch(
            final KernelStatement state,
            final int propertyKeyId,
            final Object value )
    {
        return new PrimitiveLongPredicate()
        {
            @Override
            public boolean accept( long nodeId )
            {
                try
                {
                    return nodeGetProperty( state, nodeId, propertyKeyId ).valueEquals( value );
                }
                catch ( EntityNotFoundException e )
                {
                    throw new ThisShouldNotHappenError( "Chris", "An index claims a node by id " + nodeId +
                            " has the value. However, it looks like that node does not exist.", e);
                }
            }
        };
    }

    private PrimitiveLongIterator filterIndexStateChanges( KernelStatement state, IndexDescriptor index,
            Object value, PrimitiveLongIterator nodeIds )
    {
        if ( state.hasTxStateWithChanges() )
        {
            DiffSets<Long> labelPropertyChanges = nodesWithLabelAndPropertyDiffSet( state, index, value );
            DiffSets<Long> nodes = state.txState().addedAndRemovedNodes();

            // Apply to actual index lookup
            return nodes.augmentWithRemovals( labelPropertyChanges.augment( nodeIds ) );
        }
        return nodeIds;
    }

    @Override
    public Property nodeSetProperty( KernelStatement state, long nodeId, DefinedProperty property )
            throws EntityNotFoundException
    {
        Property existingProperty = nodeGetProperty( state, nodeId, property.propertyKeyId() );
        if ( !existingProperty.isDefined() )
        {
            legacyPropertyTrackers.nodeAddStoreProperty( nodeId, property );
        }
        else
        {
            legacyPropertyTrackers.nodeChangeStoreProperty( nodeId, (DefinedProperty) existingProperty, property );
        }
        state.txState().nodeDoReplaceProperty( nodeId, existingProperty, property );
        return existingProperty;
    }

    @Override
    public Property relationshipSetProperty( KernelStatement state, long relationshipId, DefinedProperty property )
            throws EntityNotFoundException
    {
        Property existingProperty = relationshipGetProperty( state, relationshipId, property.propertyKeyId() );
        if ( !existingProperty.isDefined() )
        {
            legacyPropertyTrackers.relationshipAddStoreProperty( relationshipId, property );
        }
        else
        {
            legacyPropertyTrackers.relationshipChangeStoreProperty( relationshipId, (DefinedProperty)
                    existingProperty, property );
        }
        state.txState().relationshipDoReplaceProperty( relationshipId, existingProperty, property );
        return existingProperty;
    }

    @Override
    public Property graphSetProperty( KernelStatement state, DefinedProperty property )
    {
        Property existingProperty = graphGetProperty( state, property.propertyKeyId() );
        state.txState().graphDoReplaceProperty( existingProperty, property );
        return existingProperty;
    }

    @Override
    public Property nodeRemoveProperty( KernelStatement state, long nodeId, int propertyKeyId )
            throws EntityNotFoundException
    {
        Property existingProperty = nodeGetProperty( state, nodeId, propertyKeyId );
        if ( existingProperty.isDefined() )
        {
            legacyPropertyTrackers.nodeRemoveStoreProperty( nodeId, (DefinedProperty) existingProperty );
        }
        if(existingProperty instanceof DefinedProperty)
        {
            state.txState().nodeDoRemoveProperty( nodeId, (DefinedProperty)existingProperty );
        }
        return existingProperty;
    }

    @Override
    public Property relationshipRemoveProperty( KernelStatement state, long relationshipId, int propertyKeyId )
            throws EntityNotFoundException
    {
        Property existingProperty = relationshipGetProperty( state, relationshipId, propertyKeyId );
        if ( existingProperty.isDefined() )
        {
            legacyPropertyTrackers.relationshipRemoveStoreProperty( relationshipId, (DefinedProperty)
                    existingProperty );
        }
        if(existingProperty instanceof DefinedProperty)
        {
            state.txState().relationshipDoRemoveProperty( relationshipId, (DefinedProperty)existingProperty );
        }
        return existingProperty;
    }

    @Override
    public Property graphRemoveProperty( KernelStatement state, int propertyKeyId )
    {
        Property existingProperty = graphGetProperty( state, propertyKeyId );
        if(existingProperty instanceof DefinedProperty)
        {
            state.txState().graphDoRemoveProperty( (DefinedProperty)existingProperty );
        }
        return existingProperty;
    }

    @Override
    public PrimitiveLongIterator nodeGetPropertyKeys( KernelStatement state, long nodeId ) throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            return new PropertyKeyIdIterator( nodeGetAllProperties( state, nodeId ) );
        }

        return storeLayer.nodeGetPropertyKeys( nodeId );
    }

    @Override
    public Property nodeGetProperty( KernelStatement state, long nodeId, int propertyKeyId )
            throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            Iterator<DefinedProperty> properties = nodeGetAllProperties( state, nodeId );
            while ( properties.hasNext() )
            {
                Property property = properties.next();
                if ( property.propertyKeyId() == propertyKeyId )
                {
                    return property;
                }
            }
            return Property.noNodeProperty( nodeId, propertyKeyId );
        }

        return storeLayer.nodeGetProperty( nodeId, propertyKeyId );
    }

    @Override
    public Iterator<DefinedProperty> nodeGetAllProperties( KernelStatement state, long nodeId )
            throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            if ( state.txState().nodeIsAddedInThisTx( nodeId ) )
            {
                return state.txState().addedAndChangedNodeProperties( nodeId );
            }
            if ( state.txState().nodeIsDeletedInThisTx( nodeId ) )
            {
                // TODO Throw IllegalStateException to conform with beans API. We may want to introduce
                // EntityDeletedException instead and use it instead of returning empty values in similar places
                throw new IllegalStateException( "Node " + nodeId + " has been deleted" );
            }
            return state.txState().augmentNodeProperties( nodeId, storeLayer.nodeGetAllProperties( nodeId ) );
        }

        return storeLayer.nodeGetAllProperties( nodeId );
    }

    @Override
    public Iterator<DefinedProperty> nodeGetAllCommittedProperties( KernelStatement statement, long nodeId )
            throws EntityNotFoundException
    {
        if( statement.hasTxStateWithChanges() && statement.txState().nodeIsAddedInThisTx( nodeId ))
        {
            return Collections.emptyIterator();
        }
        return storeLayer.nodeGetAllProperties( nodeId );
    }

    @Override
    public PrimitiveLongIterator relationshipGetPropertyKeys( KernelStatement state, long relationshipId )
            throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            return new PropertyKeyIdIterator( relationshipGetAllProperties( state, relationshipId ) );
        }

        return storeLayer.relationshipGetPropertyKeys( relationshipId );
    }

    @Override
    public Property relationshipGetProperty( KernelStatement state, long relationshipId, int propertyKeyId )
            throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            Iterator<DefinedProperty> properties = relationshipGetAllProperties( state, relationshipId );
            while ( properties.hasNext() )
            {
                Property property = properties.next();
                if ( property.propertyKeyId() == propertyKeyId )
                {
                    return property;
                }
            }
            return Property.noRelationshipProperty( relationshipId, propertyKeyId );
        }
        return storeLayer.relationshipGetProperty( relationshipId, propertyKeyId );
    }

    @Override
    public Property nodeGetCommittedProperty( KernelStatement statement, long nodeId, int propertyKeyId )
            throws EntityNotFoundException
    {
        if( statement.hasTxStateWithChanges() && statement.txState().nodeIsAddedInThisTx( nodeId ))
        {
            return Property.noNodeProperty( nodeId, propertyKeyId );
        }
        return storeLayer.nodeGetProperty( nodeId, propertyKeyId );
    }

    @Override
    public Property relationshipGetCommittedProperty( KernelStatement statement, long relationshipId, int propertyKeyId )
            throws EntityNotFoundException
    {
        if( statement.hasTxStateWithChanges() && statement.txState().relationshipIsAddedInThisTx( relationshipId ))
        {
            return Property.noRelationshipProperty( relationshipId, propertyKeyId );
        }
        return storeLayer.relationshipGetProperty( relationshipId, propertyKeyId );
    }

    @Override
    public Iterator<DefinedProperty> relationshipGetAllProperties( KernelStatement state, long relationshipId )
            throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            if ( state.txState().relationshipIsAddedInThisTx( relationshipId ) )
            {
                return state.txState().addedAndChangedRelProperties( relationshipId );
            }
            if ( state.txState().relationshipIsDeletedInThisTx( relationshipId ) )
            {
                // TODO Throw IllegalStateException to conform with beans API. We may want to introduce
                // EntityDeletedException instead and use it instead of returning empty values in similar places
                throw new IllegalStateException( "Relationship " + relationshipId + " has been deleted" );
            }
            return state.txState().augmentRelProperties( relationshipId,
                    storeLayer.relationshipGetAllProperties( relationshipId ) );
        }
        else
        {
            return storeLayer.relationshipGetAllProperties( relationshipId );
        }
    }

    @Override
    public Iterator<DefinedProperty> relationshipGetAllCommittedProperties( KernelStatement statement, long relId )
            throws EntityNotFoundException
    {
        if( statement.hasTxStateWithChanges() && statement.txState().relationshipIsAddedInThisTx( relId ))
        {
            return Collections.emptyIterator();
        }
        return storeLayer.relationshipGetAllProperties( relId );
    }

    @Override
    public PrimitiveLongIterator graphGetPropertyKeys( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return new PropertyKeyIdIterator( graphGetAllProperties( state ) );
        }

        return storeLayer.graphGetPropertyKeys( state );
    }

    @Override
    public Property graphGetProperty( KernelStatement state, int propertyKeyId )
    {
        Iterator<DefinedProperty> properties = graphGetAllProperties( state );
        while ( properties.hasNext() )
        {
            Property property = properties.next();
            if ( property.propertyKeyId() == propertyKeyId )
            {
                return property;
            }
        }
        return Property.noGraphProperty( propertyKeyId );
    }

    @Override
    public Iterator<DefinedProperty> graphGetAllProperties( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().augmentGraphProperties( storeLayer.graphGetAllProperties() );
        }

        return storeLayer.graphGetAllProperties();
    }

    private DiffSets<Long> nodesWithLabelAndPropertyDiffSet( KernelStatement state, IndexDescriptor index, Object value )
    {
        TxState txState = state.txState();
        int labelId = index.getLabelId();
        int propertyKeyId = index.getPropertyKeyId();

        // Start with nodes where the given property has changed
        DiffSets<Long> diff = txState.nodesWithChangedProperty( propertyKeyId, value );

        // Ensure remaining nodes have the correct label
        HasLabelFilter hasLabel = new HasLabelFilter( state, labelId );
        diff = diff.filter( hasLabel );

        // Include newly labeled nodes that already had the correct property
        HasPropertyFilter hasPropertyFilter = new HasPropertyFilter( state, propertyKeyId, value );
        Iterator<Long> addedNodesWithLabel = txState.nodesWithLabelAdded( labelId ).iterator();
        diff.addAll( filter( hasPropertyFilter, addedNodesWithLabel ) );

        // Remove de-labeled nodes that had the correct value before
        Set<Long> removedNodesWithLabel = txState.nodesWithLabelChanged( index.getLabelId() ).getRemoved();
        diff.removeAll( filter( hasPropertyFilter, removedNodesWithLabel.iterator() ) );
        return diff;
    }

    private class HasPropertyFilter implements Predicate<Long>
    {
        private final Object value;
        private final int propertyKeyId;
        private final KernelStatement state;

        public HasPropertyFilter( KernelStatement state, int propertyKeyId, Object value )
        {
            this.state = state;
            this.value = value;
            this.propertyKeyId = propertyKeyId;
        }

        @Override
        public boolean accept( Long nodeId )
        {
            try
            {
                if ( state.hasTxStateWithChanges() && state.txState().nodeIsDeletedInThisTx( nodeId ) )
                {
                    return false;
                }
                Property property = nodeGetProperty( state, nodeId, propertyKeyId );
                return property.isDefined() && property.valueEquals( value );
            }
            catch ( EntityNotFoundException e )
            {
                return false;
            }
        }
    }

    private class HasLabelFilter implements Predicate<Long>
    {
        private final int labelId;
        private final KernelStatement state;

        public HasLabelFilter( KernelStatement state, int labelId )
        {
            this.state = state;
            this.labelId = labelId;
        }

        @Override
        public boolean accept( Long nodeId )
        {
            try
            {
                return nodeHasLabel( state, nodeId, labelId );
            }
            catch ( EntityNotFoundException e )
            {
                return false;
            }
        }
    }

    @Override
    public PrimitiveLongIterator nodeGetRelationships( KernelStatement state, long nodeId, Direction direction,
                                                       int[] relTypes ) throws EntityNotFoundException
    {
        relTypes = deduplicate( relTypes );

        if ( state.hasTxStateWithChanges() )
        {
            TxState txState = state.txState();
            PrimitiveLongIterator stored;
            if( txState.nodeIsAddedInThisTx( nodeId ) )
            {
                stored = emptyPrimitiveLongIterator();
            }
            else
            {
                stored = storeLayer.nodeListRelationships( nodeId, direction, relTypes );
            }
            return txState.augmentRelationships( nodeId, direction, relTypes, stored );
        }
        return storeLayer.nodeListRelationships( nodeId, direction, relTypes );
    }

    @Override
    public PrimitiveLongIterator nodeGetRelationships( KernelStatement state, long nodeId, Direction direction ) throws EntityNotFoundException
    {
        if ( state.hasTxStateWithChanges() )
        {
            TxState txState = state.txState();
            PrimitiveLongIterator stored;
            if( txState.nodeIsAddedInThisTx( nodeId ) )
            {
                stored = emptyPrimitiveLongIterator();
            }
            else
            {
                stored = storeLayer.nodeListRelationships( state, nodeId, direction );
            }
            return txState.augmentRelationships( nodeId, direction, stored );
        }
        return storeLayer.nodeListRelationships( state, nodeId, direction );
    }

    @Override
    public int nodeGetDegree( KernelStatement state, long nodeId, Direction direction, int relType ) throws EntityNotFoundException

    {
        if( state.hasTxStateWithChanges() )
        {
            int degree = 0;
            if(state.txState().nodeIsDeletedInThisTx( nodeId ))
            {
                return 0;
            }

            if( !state.txState().nodeIsAddedInThisTx( nodeId ))
            {
                degree = storeLayer.nodeGetDegree( nodeId, direction, relType );
            }

            return state.txState().augmentNodeDegree( nodeId, degree, direction, relType );
        }
        else
        {
            return storeLayer.nodeGetDegree( nodeId, direction, relType );
        }
    }

    @Override
    public int nodeGetDegree( KernelStatement state, long nodeId, Direction direction ) throws EntityNotFoundException
    {
        if( state.hasTxStateWithChanges() )
        {
            int degree = 0;
            if(state.txState().nodeIsDeletedInThisTx( nodeId ))
            {
                return 0;
            }

            if( !state.txState().nodeIsAddedInThisTx( nodeId ))
            {
                degree = storeLayer.nodeGetDegree( nodeId, direction );
            }

            return state.txState().augmentNodeDegree( nodeId, degree, direction );
        }
        else
        {
            return storeLayer.nodeGetDegree( nodeId, direction );
        }
    }

    @Override
    public PrimitiveIntIterator nodeGetRelationshipTypes( KernelStatement statement, long nodeId )
            throws EntityNotFoundException
    {
        if(statement.hasTxStateWithChanges() && statement.txState().nodeModifiedInThisTx(nodeId))
        {
            TxState tx = statement.txState();
            if(tx.nodeIsDeletedInThisTx( nodeId ))
            {
                return IteratorUtil.emptyPrimitiveIntIterator();
            }

            if(tx.nodeIsAddedInThisTx( nodeId ))
            {
                return tx.nodeRelationshipTypes(nodeId);
            }

            Set<Integer> types = new HashSet<>();

            // Add types in the current transaction
            PrimitiveIntIterator typesInTx = tx.nodeRelationshipTypes( nodeId );
            while(typesInTx.hasNext())
            {
                types.add( typesInTx.next() );
            }

            // Augment with types stored on disk, minus any types where all rels of that type are deleted
            // in current tx.
            PrimitiveIntIterator committedTypes = storeLayer.nodeGetRelationshipTypes( nodeId );
            while(committedTypes.hasNext())
            {
                int current = committedTypes.next();
                if(!types.contains( current ) && nodeGetDegree( statement, nodeId, Direction.BOTH, current ) > 0)
                {
                    types.add( current );
                }
            }

            return IteratorUtil.toPrimitiveIntIterator( types.iterator() );
        }
        else
        {
            return storeLayer.nodeGetRelationshipTypes( nodeId );
        }
    }

    //
    // Methods that delegate directly to storage
    //

    @Override
    public Long indexGetOwningUniquenessConstraintId( KernelStatement state, IndexDescriptor index )
            throws SchemaRuleNotFoundException
    {
        return storeLayer.indexGetOwningUniquenessConstraintId( index );
    }

    @Override
    public long indexGetCommittedId( KernelStatement state, IndexDescriptor index, SchemaStorage.IndexRuleKind kind )
            throws SchemaRuleNotFoundException
    {
        return storeLayer.indexGetCommittedId( index, kind );
    }

    @Override
    public String indexGetFailure( Statement state, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return storeLayer.indexGetFailure( descriptor );
    }

    @Override
    public int labelGetForName( Statement state, String labelName )
    {
        return storeLayer.labelGetForName( labelName );
    }

    @Override
    public String labelGetName( Statement state, int labelId ) throws LabelNotFoundKernelException
    {
        return storeLayer.labelGetName( labelId );
    }

    @Override
    public int propertyKeyGetForName( Statement state, String propertyKeyName )
    {
        return storeLayer.propertyKeyGetForName( propertyKeyName );
    }

    @Override
    public String propertyKeyGetName( Statement state, int propertyKeyId ) throws PropertyKeyIdNotFoundKernelException
    {
        return storeLayer.propertyKeyGetName( propertyKeyId );
    }

    @Override
    public Iterator<Token> propertyKeyGetAllTokens( Statement state )
    {
        return storeLayer.propertyKeyGetAllTokens();
    }

    @Override
    public Iterator<Token> labelsGetAllTokens( Statement state )
    {
        return storeLayer.labelsGetAllTokens();
    }

    @Override
    public int relationshipTypeGetForName( Statement state, String relationshipTypeName )
    {
        return storeLayer.relationshipTypeGetForName( relationshipTypeName );
    }

    @Override
    public String relationshipTypeGetName( Statement state, int relationshipTypeId ) throws
            RelationshipTypeIdNotFoundKernelException
    {
        return storeLayer.relationshipTypeGetName( relationshipTypeId );
    }

    @Override
    public int labelGetOrCreateForName( Statement state, String labelName ) throws IllegalTokenNameException,
            TooManyLabelsException
    {
        return storeLayer.labelGetOrCreateForName( labelName );
    }

    @Override
    public int propertyKeyGetOrCreateForName( Statement state, String propertyKeyName ) throws IllegalTokenNameException
    {
        return storeLayer.propertyKeyGetOrCreateForName( propertyKeyName );
    }

    @Override
    public int relationshipTypeGetOrCreateForName( Statement state, String relationshipTypeName ) throws IllegalTokenNameException
    {
        return storeLayer.relationshipTypeGetOrCreateForName( relationshipTypeName );
    }

    private static int[] deduplicate( int[] types )
    {
        int unique = 0;
        for ( int i = 0; i < types.length; i++ )
        {
            int type = types[i];
            for ( int j = 0; j < unique; j++ )
            {
                if ( type == types[j] )
                {
                    type = -1; // signal that this relationship is not unique
                    break; // we will not find more than one conflict
                }
            }
            if ( type != -1 )
            { // this has to be done outside the inner loop, otherwise we'd never accept a single one...
                types[unique++] = types[i];
            }
        }
        if ( unique < types.length )
        {
            types = Arrays.copyOf( types, unique );
        }
        return types;
    }
}
