/*******************************************************************************
 * Copyright (c) 2004 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *******************************************************************************/

package org.eclipse.birt.data.engine.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.birt.core.data.DataType;
import org.eclipse.birt.core.data.ExpressionUtil;
import org.eclipse.birt.core.script.ScriptContext;
import org.eclipse.birt.data.engine.api.IBaseExpression;
import org.eclipse.birt.data.engine.api.IBaseQueryDefinition;
import org.eclipse.birt.data.engine.api.IBaseQueryResults;
import org.eclipse.birt.data.engine.api.IBinding;
import org.eclipse.birt.data.engine.api.IComputedColumn;
import org.eclipse.birt.data.engine.api.IConditionalExpression;
import org.eclipse.birt.data.engine.api.IFilterDefinition;
import org.eclipse.birt.data.engine.api.IGroupDefinition;
import org.eclipse.birt.data.engine.api.IQueryDefinition;
import org.eclipse.birt.data.engine.api.IResultMetaData;
import org.eclipse.birt.data.engine.api.ISortDefinition;
import org.eclipse.birt.data.engine.api.querydefn.Binding;
import org.eclipse.birt.data.engine.api.querydefn.ComputedColumn;
import org.eclipse.birt.data.engine.api.querydefn.ConditionalExpression;
import org.eclipse.birt.data.engine.api.querydefn.FilterDefinition;
import org.eclipse.birt.data.engine.api.querydefn.ScriptExpression;
import org.eclipse.birt.data.engine.api.script.IDataSourceInstanceHandle;
import org.eclipse.birt.data.engine.core.DataException;
import org.eclipse.birt.data.engine.core.security.PropertySecurity;
import org.eclipse.birt.data.engine.executor.BaseQuery;
import org.eclipse.birt.data.engine.executor.JointDataSetQuery;
import org.eclipse.birt.data.engine.expression.ExpressionCompilerUtil;
import org.eclipse.birt.data.engine.expression.ExpressionProcessor;
import org.eclipse.birt.data.engine.i18n.ResourceConstants;
import org.eclipse.birt.data.engine.impl.aggregation.AggregateTable;
import org.eclipse.birt.data.engine.impl.group.GroupCalculatorFactory;
import org.eclipse.birt.data.engine.odi.ICandidateQuery;
import org.eclipse.birt.data.engine.odi.IDataSource;
import org.eclipse.birt.data.engine.odi.IEventHandler;
import org.eclipse.birt.data.engine.odi.IPreparedDSQuery;
import org.eclipse.birt.data.engine.odi.IQuery;
import org.eclipse.birt.data.engine.odi.IResultIterator;
import org.eclipse.birt.data.engine.odi.IResultObjectEvent;
import org.eclipse.birt.data.engine.olap.api.ICubeQueryResults;
import org.eclipse.birt.data.engine.olap.script.JSCubeBindingObject;
import org.eclipse.birt.data.engine.script.OnFetchScriptHelper;
import org.eclipse.birt.data.engine.script.ScriptConstants;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import com.ibm.icu.text.Collator;

/**
 * 
 */
public abstract class QueryExecutor implements IQueryExecutor
{

	private IBaseQueryDefinition baseQueryDefn;
	private AggregateTable aggrTable;
	
	// from PreparedQuery->PreparedDataSourceQuery->DataEngineImpl
	private Scriptable sharedScope;
	/** Externally provided query scope; can be null */
	// from PreparedQuery->PreparedDataSourceQuery
	private Scriptable parentScope;

	// for query execution
	private Scriptable queryScope;

	private boolean isPrepared = false;
	private boolean isExecuted = false;
	
	private boolean loadFromCache;
	
	private Map queryAppContext;

	/** Query nesting level, 1 - outermost query */
	private int nestedLevel = 1;

	/** Runtime data source and data set used by this instance of executor */
	protected DataSourceRuntime dataSource;
	protected DataSetRuntime dataSet;

	protected IDataSource odiDataSource;
	protected IQuery odiQuery;

	/** Outer query's results; null if this query is not nested */
	protected IQueryService tabularOuterResults;
	private IResultIterator odiResult;
	private IExecutorHelper parentHelper;
	private DataEngineSession session;
	protected List temporaryComputedColumns = new ArrayList( );
	private static Logger logger = Logger.getLogger( QueryExecutor.class.getName( ) );

	/**
	 * @param sharedScope
	 * @param baseQueryDefn
	 * @param aggrTable
	 */
	QueryExecutor( Scriptable sharedScope, IBaseQueryDefinition baseQueryDefn,
			AggregateTable aggrTable, DataEngineSession session )
	{
		Object[] params = {
				sharedScope, baseQueryDefn, aggrTable, session
		};
		logger.entering( QueryExecutor.class.getName( ),
				"QueryExecutor",
				params );
		this.sharedScope = sharedScope;
		this.baseQueryDefn = baseQueryDefn;
		this.aggrTable = aggrTable;
		this.session = session;
		logger.exiting( QueryExecutor.class.getName( ), "QueryExecutor" );
	}

	/**
	 * Provide the actual DataSourceRuntime used for the query.
	 * 
	 * @return
	 */
	abstract protected DataSourceRuntime findDataSource( ) throws DataException;

	/**
	 * Create a new instance of data set runtime
	 * 
	 * @return
	 */
	abstract protected DataSetRuntime newDataSetRuntime( ) throws DataException;

	/**
	 * Create a new unopened odiDataSource given the data source runtime
	 * definition
	 * 
	 * @return
	 */
	abstract protected IDataSource createOdiDataSource( ) throws DataException;

	/**
	 * Create an empty instance of odi query
	 * 
	 * @return
	 */
	abstract protected IQuery createOdiQuery( ) throws DataException;

	/**
	 * Prepares the ODI query
	 */
	protected void prepareOdiQuery( ) throws DataException
	{
	}
	
	/**
	 * 
	 * @throws DataException
	 */
	protected void dataSourceBeforeOpen( ) throws DataException
	{
		if ( !this.loadFromCache )
		{
			this.dataSource.beforeOpen( );
		}
	}
	
	/**
	 * 
	 * @throws DataException
	 */
	protected void dataSourceAfterOpen( ) throws DataException
	{
		if ( !this.loadFromCache )
		{
			this.dataSource.afterOpen( );
		}
	}

	/**
	 * 
	 * @throws DataException
	 */
	protected void dataSetBeforeOpen( ) throws DataException
	{
		if ( !this.loadFromCache )
		{
			this.dataSet.beforeOpen( );
		}
	}
	
	/**
	 * 
	 * @throws DataException
	 */
	protected void dataSetAfterOpen( ) throws DataException
	{
		if ( !this.loadFromCache )
		{
			this.dataSet.afterOpen( );
		}
	}
	
	/**
	 * 
	 * @throws DataException
	 */
	protected void dataSetBeforeClose( ) throws DataException
	{
		if ( !this.loadFromCache )
		{
			dataSet.beforeClose( );
		}
	}
	
	/**
	 * 
	 * @throws DataException
	 */
	protected void dataSetAfterClose( ) throws DataException
	{
		if ( !this.loadFromCache )
		{
			this.dataSet.afterClose( );
		}
	}
	
	/**
	 * Executes the ODI query to reproduce a ODI result set
	 * @param eventHandler 
	 * @param stopSign
	 * @return
	 */
	abstract protected IResultIterator executeOdiQuery(
			IEventHandler eventHandler, StopSign stopSign ) throws DataException;

	/**
	 * @param context
	 */
	void setAppContext( Map context )
	{
		queryAppContext = context;
	}

	/**
	 * Prepare Executor so that it is ready to execute the query
	 * 
	 * @param outerRts
	 * @param targetScope
	 * @throws DataException
	 */
	void prepareExecution( IBaseQueryResults outerRts, Scriptable targetScope )
			throws DataException
	{
		if ( isPrepared )
			return;

		this.parentScope = targetScope;
		dataSource = findDataSource( );

		if ( outerRts != null && ( outerRts instanceof IQueryService || outerRts instanceof ICubeQueryResults ))
		{
			if ( outerRts instanceof IQueryService )
			{
				tabularOuterResults = ( (IQueryService) outerRts );
				if ( tabularOuterResults.isClosed( ) )
				{
					// Outer result is closed; invalid
					throw new DataException( ResourceConstants.RESULT_CLOSED );
				}
				this.nestedLevel = tabularOuterResults.getNestedLevel( );
				// TODO: check helper is null
				IExecutorHelper helper = tabularOuterResults.getExecutorHelper( );
				this.setParentExecutorHelper( helper );
			}
			else if( outerRts instanceof ICubeQueryResults )
			{
				ExecutorHelper helper = new ExecutorHelper( null );
				helper.setScriptable( new JSCubeBindingObject( ( (ICubeQueryResults) outerRts ).getCubeCursor( ) ) );
				this.setParentExecutorHelper( helper );
			}
		}

		// Create the data set runtime
		// Since data set runtime contains the execution result, a new data set
		// runtime is needed for each execute
		dataSet = newDataSetRuntime( );
		assert dataSet != null;	
		
		//For cached data set, we need not execute any scripts.
		loadFromCache = loadFromCache( );
		
		dataSet.setFromCache( loadFromCache );
		
		openDataSource( );
		
		// Run beforeOpen script now so the script can modify the
		// DataSetRuntime properties
		dataSetBeforeOpen( );

		// Let subclass create a new and empty intance of the appropriate
		// odi IQuery
		odiQuery = createOdiQuery( );
		odiQuery.setDistinctValueFlag( dataSet.needDistinctValue( ) );
		odiQuery.setQueryDefinition( this.baseQueryDefn );
		odiQuery.setExprProcessor( new ExpressionProcessor( dataSet ) );
		
		//Set the row fetch limit for the IQuery instance.The row fetch limit
		//is the number of rows that a data set can fetch from data source.
		if( dataSet.getDesign( ) != null )
		{
			//When it is not a subquery, the property "row fetch limit" should be applied
			//to the query.
			odiQuery.setRowFetchLimit( dataSet.getDesign( ).getRowFetchLimit( ) );
		}
		populateOdiQuery( );
		prepareOdiQuery( );
		isPrepared = true;
	}
	
	/**
	 * 
	 * @return
	 * @throws DataException
	 */
	private boolean loadFromCache( ) throws DataException
	{
		if( this.dataSource == null )
			return false;
		if ( !( this.baseQueryDefn instanceof IQueryDefinition ) )
			return false;
		return this.session.getDataSetCacheManager( )
			.doesLoadFromCache( this.dataSource.getDesign( ),
				this.dataSet.getDesign( ),
				new ParameterUtil( this.tabularOuterResults == null
						? null
						: this.tabularOuterResults.getQueryScope( ),
						this.dataSet,
						( IQueryDefinition )this.baseQueryDefn,
						this.getQueryScope( ),
						session.getEngineContext( ).getScriptContext( )).resolveDataSetParameters( true ),
				this.queryAppContext );
	}

	/**
	 * Open the required DataSource. This method should be called after
	 * "dataSource" is initialized by findDataSource() method.
	 * 
	 * @throws DataException
	 */
	protected void openDataSource( ) throws DataException
	{
		assert odiDataSource == null;

		// Open the underlying data source
		// dataSource = findDataSource( );
		if ( dataSource != null )
		{
			// TODO: potential bug
			if ( !dataSource.isOpen( )
					|| session.getDataSetCacheManager( ).needsToCache( ))
			{
				// Data source is not open; create an Odi Data Source and open it
				// We should run the beforeOpen script now to give it a chance to modify
				// runtime data source properties
				dataSourceBeforeOpen( );

				// Let subclass create a new unopened odi data source
				odiDataSource = createOdiDataSource( );

				// Passes thru the prepared query executor's 
				// context to the new odi data source
				odiDataSource.setAppContext( queryAppContext );

				// Open the odi data source
				dataSource.openOdiDataSource( odiDataSource );

				dataSourceAfterOpen( );
			}
			else
			{
				// Use existing odiDataSource created for the data source runtime
				odiDataSource = dataSource.getOdiDataSource( );

				// Passes thru the prepared query executor's 
				// current context to existing data source
				odiDataSource.setAppContext( queryAppContext );
			}
		}
	}

	/**
	 * Populates odiQuery with this query's definitions
	 * 
	 * @throws DataException
	 */
	protected void populateOdiQuery( ) throws DataException
	{
		assert odiQuery != null;
		assert this.baseQueryDefn != null;

		// Set grouping
		populateGrouping( session.getEngineContext( ).getScriptContext( ).getContext( ) );

		// Set sorting
		populateSorting( );

		// set fetch event
		populateFetchEvent( session.getEngineContext( ).getScriptContext( ) );

		// specify max rows the query should fetch
		odiQuery.setMaxRows( this.baseQueryDefn.getMaxRows( ) );

		prepareCacheQuery( this.odiQuery );
		
	}

	/**
	 * TODO: enhance me, this is only a temp logic
	 * Set temporary computed columns to DataSourceQuery where cache is used
	 */
	protected void prepareCacheQuery( IQuery odiQuery )
	{
		if ( temporaryComputedColumns != null
				&& temporaryComputedColumns.size( ) > 0 )
		{
			if ( odiQuery instanceof org.eclipse.birt.data.engine.executor.dscache.DataSourceQuery )
			{
				( (org.eclipse.birt.data.engine.executor.dscache.DataSourceQuery) odiQuery ).setTempComputedColumn( this.temporaryComputedColumns );
			}
			else if ( odiQuery instanceof org.eclipse.birt.data.engine.executor.dscache.CandidateQuery )
			{
				( (org.eclipse.birt.data.engine.executor.dscache.CandidateQuery) odiQuery ).setTempComputedColumn( this.temporaryComputedColumns );
			}
		}
	}
	
	/**
	 * Populate grouping to the query.
	 * 
	 * @param cx
	 * @throws DataException
	 */
	private void populateGrouping( Context cx ) throws DataException
	{
		List groups = this.baseQueryDefn.getGroups( );
		if ( groups != null && !groups.isEmpty( ) )
		{
			IQuery.GroupSpec[] groupSpecs = new IQuery.GroupSpec[groups.size( )];
			Iterator it = groups.iterator( );
			for ( int i = 0; it.hasNext( ); i++ )
			{
				IGroupDefinition src = (IGroupDefinition) it.next( );

				validateGroupExpression( src );

				String expr = getGroupKeyExpression( src );
				
				String groupName = populateGroupName( i, expr );
				
				IQuery.GroupSpec dest = QueryExecutorUtil.groupDefnToSpec( cx,
						src,
						expr,
						groupName,
						-1,
						this.baseQueryDefn.getQueryExecutionHints( ) == null
								? true
								: this.baseQueryDefn.getQueryExecutionHints( )
										.doSortBeforeGrouping( ) );
				
				int dataType = getColumnDataType( cx, expr );
				groupSpecs[i] = dest;
				this.temporaryComputedColumns.add( getComputedColumnInstance( cx,
						groupSpecs[i].getInterval( ),
						src,
						expr,
						groupName,
						dest,
						dataType) );

			}
			odiQuery.setGrouping( Arrays.asList( groupSpecs ) );
		}
	}

	/**
	 * Validating the group expression.
	 * 
	 * @param src
	 * @throws DataException
	 */
	private void validateGroupExpression( IGroupDefinition src ) throws DataException
	{
		if ( ( src.getKeyColumn( ) == null || src.getKeyColumn( )
				.trim( )
				.length( ) == 0 )
				&& ( src.getKeyExpression( ) == null || src.getKeyExpression( )
						.trim( )
						.length( ) == 0 ) )
			throw new DataException( ResourceConstants.BAD_GROUP_EXPRESSION );
	}
	

	/**
	 * Populate the group name according to the given expression.
	 * 
	 * @param i
	 * @param expr
	 * @return
	 */
	private String populateGroupName( int i, String expr )
	{
		String groupName;
		if ( expr.trim( ).equalsIgnoreCase( "row[0]" )
				|| expr.trim( ).equalsIgnoreCase( "row._rowPosition" )
				|| expr.trim( ).equalsIgnoreCase( "dataSetRow[0]" )
				|| expr.trim( )
						.equalsIgnoreCase( "dataSetRow._rowPosition" ) )
		{
			groupName = "_{$TEMP_GROUP_" + i + "ROWID$}_";
		}
		else
		{
			groupName = "_{$TEMP_GROUP_" + i + "$}_";
		}
		return groupName;
	}

	/**
	 * Get the computed column instance according to the group type.If group has
	 * interval, return GroupComputedColumn, otherwise return normal computed
	 * column.
	 * 
	 * @param cx
	 * @param groupSpecs
	 * @param i
	 * @param src
	 * @param expr
	 * @param groupName
	 * @param dest
	 * @return
	 * @throws DataException
	 */
	private IComputedColumn getComputedColumnInstance( Context cx,
			int interval, IGroupDefinition src,
			String expr, String groupName, IQuery.GroupSpec dest,
			int dataType)
			throws DataException
	{
		if ( dest.getInterval( ) != IGroupDefinition.NO_INTERVAL )
		{
			return new GroupComputedColumn( groupName,
					expr,
					QueryExecutorUtil.getTempComputedColumnType( interval ),
					GroupCalculatorFactory.getGroupCalculator( src.getInterval( ),
							src.getIntervalStart( ),
							src.getIntervalRange( ),
							dataType) );

		}
		else
		{
			return new ComputedColumn( groupName,
					expr,
					QueryExecutorUtil.getTempComputedColumnType( interval ) );
		}
	}

	/**
	 * Populate the sortings in a query.
	 * 
	 * @throws DataException
	 */
	private void populateSorting( ) throws DataException
	{
		List sorts = this.baseQueryDefn.getSorts( );
		if ( sorts != null && !sorts.isEmpty( ) )
		{
			IQuery.SortSpec[] sortSpecs = new IQuery.SortSpec[sorts.size( )];
			Iterator it = sorts.iterator( );
			for ( int i = 0; it.hasNext( ); i++ )
			{
				ISortDefinition src = (ISortDefinition) it.next( );
				int sortIndex = -1;
				String sortKey = src.getColumn( );
				if ( sortKey == null )
					sortKey = src.getExpression( ).getText( );
				else
				{
					sortKey = getColumnRefExpression( sortKey );
				}

				temporaryComputedColumns.add( new ComputedColumn( "_{$TEMP_SORT_"
						+ i + "$}_",
						sortKey,
						DataType.ANY_TYPE ) );
				sortIndex = -1;
				sortKey = String.valueOf( "_{$TEMP_SORT_" + i + "$}_");

				IQuery.SortSpec dest = new IQuery.SortSpec( sortIndex,
						sortKey,
						src.getSortDirection( ) == ISortDefinition.SORT_ASC,
						src.getSortStrength( ) == -1? null:Collator.getInstance( ));
				sortSpecs[i] = dest;
			}
			odiQuery.setOrdering( Arrays.asList( sortSpecs ) );
		}
	}
	
	/**
	 * 
	 * @param cx
	 * @throws DataException
	 */
	private void populateFetchEvent( ScriptContext cx ) throws DataException
	{
		List dataSetFilters = new ArrayList( );
		List queryFilters = new ArrayList( );
		List aggrFilters = new ArrayList( );
		List dataSetAggrFilters = new ArrayList( );
		if ( dataSet.getFilters( ) != null )
		{
			Map bindings = createBindingFromComputedColumn( dataSet.getComputedColumns( ));
			for ( int i = 0; i < dataSet.getFilters( ).size( ); i++ )
			{
				if ( QueryExecutorUtil.isAggrFilter( (IFilterDefinition) dataSet.getFilters( )
						.get( i ),
						bindings ) )
				{
					dataSetAggrFilters.add( dataSet.getFilters( ).get( i ) );
				}
				else
				{
					dataSetFilters.add( dataSet.getFilters( ).get( i ) );
				}
			}
		}

		if ( this.baseQueryDefn.getFilters( ) != null )
		{
			for ( int i = 0; i < this.baseQueryDefn.getFilters( ).size( ); i++ )
			{
				if ( QueryExecutorUtil.isAggrFilter( (IFilterDefinition) this.baseQueryDefn.getFilters( )
						.get( i ),
						this.baseQueryDefn.getBindings( ) ) )
				{
					aggrFilters.add( this.baseQueryDefn.getFilters( ).get( i ) );
				}
				else
				{
					queryFilters.add( this.baseQueryDefn.getFilters( ).get( i ) );
				}
			}
		}

		//When prepare filters, the temporaryComputedColumns would also be effect.
		List multipassFilters = prepareFilters( cx,
				dataSetFilters,
				queryFilters,
				temporaryComputedColumns );

		//******************populate the onFetchEvent below**********************/		    
		List computedColumns = null;
		// set computed column event
		computedColumns = this.dataSet.getComputedColumns( );
		if ( computedColumns == null )
			computedColumns = new ArrayList( );
		if ( computedColumns.size( ) > 0
				|| temporaryComputedColumns.size( ) > 0 )
		{
			IResultObjectEvent objectEvent = new ComputedColumnHelper( this.dataSet,
					computedColumns,
					temporaryComputedColumns, cx );
			odiQuery.addOnFetchEvent( objectEvent );
			this.dataSet.getComputedColumns( )
					.addAll( temporaryComputedColumns );
		}
		if ( dataSet.getEventHandler( ) != null )
		{
			OnFetchScriptHelper event = new OnFetchScriptHelper( dataSet );
			odiQuery.addOnFetchEvent( event );
		}

		if ( dataSetFilters.size( )
				+ queryFilters.size( ) + multipassFilters.size( ) + aggrFilters.size( ) + dataSetAggrFilters.size( ) > 0 )
		{
			IResultObjectEvent objectEvent = new FilterByRow( dataSetFilters,
					queryFilters,
					multipassFilters,
					aggrFilters,
					dataSetAggrFilters,
					dataSet );
			odiQuery.addOnFetchEvent( objectEvent );
		}
	}
	
	/**
	 * 
	 * @param computedColumns
	 * @return
	 * @throws DataException
	 */
	private Map<String, IBinding> createBindingFromComputedColumn( List computedColumns ) throws DataException
	{
		Map<String, IBinding> result = PropertySecurity.createHashMap( );
		if( computedColumns == null || computedColumns.size( ) == 0 )
			return result;
		for( Object computedColumn: computedColumns )
		{
			IComputedColumn cc = (IComputedColumn)computedColumn;
			IBinding binding = new Binding( cc.getName( ) );
			binding.setExpression( cc.getExpression( ) );
			binding.setAggrFunction( cc.getAggregateFunction( ) );
			result.put( cc.getName( ), binding );
		}
		return result;
	}
	
	/**
	 * get the data type of a expression
	 * @param cx
	 * @param expr
	 * @return
	 * @throws DataException 
	 */
	private int getColumnDataType( Context cx, String expr ) throws DataException
	{
		String columnName = QueryExecutorUtil.getColInfoFromJSExpr( cx, expr )
				.getColumnName( );
		if ( columnName == null )
		{
			return DataType.UNKNOWN_TYPE;
		}
		if ( columnName.equals( ScriptConstants.ROW_NUM_KEYWORD ) )
		{
			return DataType.INTEGER_TYPE;
		}
		Object baseExpr = ( this.baseQueryDefn.getBindings( ).get( columnName ) );

		if ( baseExpr == null )
		{
			return DataType.UNKNOWN_TYPE;
		}

		return ( (IBinding) baseExpr ).getExpression( ).getDataType( );
	}
	
	/**
	 * @param src
	 * @return
	 */
	private String getGroupKeyExpression( IGroupDefinition src )
	{
		String expr = src.getKeyColumn( );
		if ( expr == null )
		{
			expr = src.getKeyExpression( );
		}
		else
		{
			expr = getColumnRefExpression( expr );
		}
		return expr;
	}

	/**
	 * 
	 * @param expr
	 * @return
	 */
	private String getColumnRefExpression( String expr )
	{
		return ExpressionUtil.createJSRowExpression( expr );
	}

	void setParentExecutorHelper( IExecutorHelper helper )
	{
		this.parentHelper = helper;
	}

	/**
	 * 
	 * @param cx
	 * @param dataSetFilters
	 * @param queryFilters
	 * @param temporaryComputedColumns
	 * @return
	 * @throws DataException 
	 */
	private List prepareFilters( ScriptContext cx, List dataSetFilters,
			List queryFilters, List temporaryComputedColumns ) throws DataException
	{
		List result = new ArrayList( );
		prepareFilter( cx, dataSetFilters, temporaryComputedColumns, result );
		prepareFilter( cx, queryFilters, temporaryComputedColumns, result );
		return result;
	}

	/**
	 * 
	 * @param cx
	 * @param dataSetFilters
	 * @param temporaryComputedColumns
	 * @param result
	 * @throws DataException 
	 */
	private void prepareFilter( ScriptContext cx, List dataSetFilters,
			List temporaryComputedColumns, List result ) throws DataException
	{
		if ( dataSetFilters != null && !dataSetFilters.isEmpty( ) )
		{
			Iterator it = dataSetFilters.iterator( );
			for ( int i = 0; it.hasNext( ); i++ )
			{
				IFilterDefinition src = (IFilterDefinition) it.next( );
				IBaseExpression expr = src.getExpression( );

				if ( isGroupFilter( src ) )
				{
					ConditionalExpression ce = ( (ConditionalExpression) expr );
					String exprText = ce.getExpression( ).getText( );
					ColumnInfo columnInfo = QueryExecutorUtil.getColInfoFromJSExpr( cx.getContext( ),
							exprText );

					int index = columnInfo.getColumnIndex( );
					String name = columnInfo.getColumnName( );

					if ( name == null && index < 0 )
					{
						// If failed to treate filter key as a column reference
						// expression
						// then treat it as a computed column expression
						temporaryComputedColumns.add( new ComputedColumn( "_{$TEMP_FILTER_"
								+ i + "$}_",
								exprText,
								DataType.ANY_TYPE ) );
						it.remove( );
						result.add( new FilterDefinition( new ConditionalExpression( new ScriptExpression( String.valueOf( "dataSetRow[\"_{$TEMP_FILTER_"
								+ i + "$}_\"]" ) ),
								ce.getOperator( ),
								ce.getOperand1( ),
								ce.getOperand2( ) ) ) );
					}
				}

			}
		}
	}

	/**
	 * 
	 * @param filter
	 * @return
	 * @throws DataException 
	 */
	private boolean isGroupFilter( IFilterDefinition filter ) throws DataException
	{
		IBaseExpression expr = filter.getExpression( );

		if ( expr instanceof IConditionalExpression )
		{
			if ( !ExpressionCompilerUtil.isValidExpressionInQueryFilter( expr,
					session.getEngineContext( )
							.getScriptContext( )
							.getContext( ) ) )
				throw new DataException( ResourceConstants.INVALID_DEFINITION_IN_FILTER,
						new Object[]{
							( (IConditionalExpression) expr ).getExpression( )
									.getText( )
						} );
			try
			{
				if ( odiQuery instanceof BaseQuery )
				{
					return ( (BaseQuery) odiQuery ).getExprProcessor( )
								.hasAggregation( expr );
				}
			}
			catch ( DataException e )
			{
				return true;
			}
		}
		return false;
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getResultMetaData()
	 */
	public IResultMetaData getResultMetaData( ) throws DataException
	{
		assert odiQuery instanceof IPreparedDSQuery
				|| odiQuery instanceof ICandidateQuery
				|| odiQuery instanceof JointDataSetQuery;

		if ( odiQuery instanceof IPreparedDSQuery )
		{
			if ( ( (IPreparedDSQuery) odiQuery ).getResultClass( ) != null )
				return new ResultMetaData( ( (IPreparedDSQuery) odiQuery ).getResultClass( ) );
			else
				return null;
		}
		else if ( odiQuery instanceof JointDataSetQuery )
		{
			return new ResultMetaData( ( (JointDataSetQuery) odiQuery ).getResultClass( ) );
		}
		else
		{
			return new ResultMetaData( ( (ICandidateQuery) odiQuery ).getResultClass( ) );
		}
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#execute()
	 */
	public void execute( IEventHandler eventHandler, StopSign stopSign ) throws DataException
	{
		logger.logp( Level.FINER,
				QueryExecutor.class.getName( ),
				"execute",
				"Start to execute" );

		if ( this.isExecuted )
			return;

		ExecutorHelper helper = new ExecutorHelper( this.parentHelper );
		
		eventHandler.setExecutorHelper( helper );

		// Execute the query
		odiResult = executeOdiQuery( eventHandler, stopSign );

		helper.setScriptable( this.dataSet.getJSResultRowObject( ) );
		
		resetComputedColumns( );
		// Bind the row object to the odi result set
		this.dataSet.setResultSet( odiResult, false );

		// Calculate aggregate values
		//this.aggrTable.calculate( odiResult, getQueryScope( ) );
		
		this.isExecuted = true;

		logger.logp( Level.FINER,
				QueryExecutor.class.getName( ),
				"execute",
				"Finish executing" );
	}

	/**
	 * reset computed columns
	 */
	private void resetComputedColumns( )
	{
		List l = this.getDataSet( ).getComputedColumns( );
		if ( l != null )
			l.removeAll( this.temporaryComputedColumns );
	}

	/*
	 * Closes the executor; release all odi resources
	 * 
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#close()
	 */
	public void close( )
	{
		if ( odiQuery == null )
		{
			// already closed
			logger.logp( Level.FINER,
					QueryExecutor.class.getName( ),
					"close",
					"executor closed " );
			return;
		}

		// Close the data set and associated odi query
		try
		{
			dataSetBeforeClose( );
		}
		catch ( DataException e )
		{
			logger.logp( Level.FINE,
					QueryExecutor.class.getName( ),
					"close",
					e.getMessage( ),
					e );
		}

		if ( odiResult != null )
		{	
			try
			{
				odiResult.close( );
			}
			catch ( DataException e1 )
			{
				// TODO Auto-generated catch block
				e1.printStackTrace( );
			}
		}
		odiQuery.close( );

		try
		{
			dataSet.close( );
		}
		catch ( DataException e )
		{
			logger.logp( Level.FINE,
					QueryExecutor.class.getName( ),
					"close",
					e.getMessage( ),
					e );
		}

		odiQuery = null;
		odiDataSource = null;
		odiResult = null;
		queryScope = null;
		isPrepared = false;
		isExecuted = false;

		// Note: reset dataSet and dataSource only after afterClose() is executed, since
		// the script may access these two objects
		try
		{
			dataSetAfterClose( );
		}
		catch ( DataException e )
		{
			logger.logp( Level.FINE,
					QueryExecutor.class.getName( ),
					"close",
					e.getMessage( ),
					e );
		}
		dataSet = null;
		dataSource = null;

		logger.logp( Level.FINER,
				QueryExecutor.class.getName( ),
				"close",
				"executor closed " );
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getDataSet()
	 */
	public DataSetRuntime getDataSet( )
	{
		return dataSet;
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getSharedScope()
	 */
	public Scriptable getSharedScope( )
	{
		return this.sharedScope;
	}

	/**
	 * Gets the Javascript scope for evaluating expressions for this query
	 * 
	 * @return
	 */
	public Scriptable getQueryScope( )
	{
		if ( queryScope == null )
		{
			// Set up a query scope. All expressions are evaluated against the 
			// Data set JS object as the prototype (so that it has access to all
			// data set properties). It uses a subscope of the externally provided
			// parent scope, or the global shared scope
			queryScope = newSubScope( parentScope );
			queryScope.setPrototype( dataSet.getJSDataSetObject( ) );
		}
		return queryScope;
	}

	/**
	 * Creates a subscope within parent scope
	 * @param parentAndProtoScope parent scope. If null, the shared top-level scope is used as parent
	 */
	private Scriptable newSubScope( Scriptable parentAndProtoScope )
	{
		if ( parentAndProtoScope == null )
			parentAndProtoScope = sharedScope;

		Scriptable scope = session.getEngineContext( )
				.getScriptContext( )
				.getContext( )
				.newObject( parentAndProtoScope );
		scope.setParentScope( parentAndProtoScope );
		scope.setPrototype( parentAndProtoScope );
		return scope;
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getNestedLevel()
	 */
	public int getNestedLevel( )
	{
		return this.nestedLevel;
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getDataSourceInstanceHandle()
	 */
	public IDataSourceInstanceHandle getDataSourceInstanceHandle( )
	{
		return this.dataSource;
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getJSAggrValueObject()
	 */
	public Scriptable getJSAggrValueObject( )
	{
		return this.aggrTable.getJSAggrValueObject( );
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getNestedDataSets(int)
	 */
	public DataSetRuntime[] getNestedDataSets( int nestedCount )
	{
		return tabularOuterResults == null ? null
				: tabularOuterResults.getDataSetRuntime( nestedCount );
	}

	/*
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getOdiResultSet()
	 */
	public IResultIterator getOdiResultSet( )
	{
		return this.odiResult;
	}
	
	/**
	 * @param evaluateValue
	 * @return
	 * @throws DataException
	 */
	protected Collection resolveDataSetParameters( boolean evaluateValue )
			throws DataException
	{
		return new ParameterUtil( this.tabularOuterResults == null ? null:this.tabularOuterResults.getQueryScope( ),
				this.getDataSet( ),
				(IQueryDefinition) this.baseQueryDefn,
				this.getQueryScope( ),
				session.getEngineContext( ).getScriptContext( )).resolveDataSetParameters( evaluateValue );
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.eclipse.birt.data.engine.impl.IQueryExecutor#getAppContext()
	 */
	public Map getAppContext()
	{
		return this.queryAppContext;
	}
	
	public DataEngineSession getSession()
	{
		return this.session;
	}
}