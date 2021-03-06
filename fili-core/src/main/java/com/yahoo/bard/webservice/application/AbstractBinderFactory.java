// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE file distributed with this work for terms.
package com.yahoo.bard.webservice.application;

import static com.yahoo.bard.webservice.config.BardFeatureFlag.DRUID_COORDINATOR_METADATA;
import static com.yahoo.bard.webservice.config.BardFeatureFlag.DRUID_DIMENSIONS_LOADER;
import static com.yahoo.bard.webservice.web.handlers.CacheRequestHandler.CACHE_HITS;
import static com.yahoo.bard.webservice.web.handlers.CacheRequestHandler.CACHE_REQUESTS;
import static com.yahoo.bard.webservice.web.handlers.DefaultWebServiceHandlerSelector.QUERY_REQUEST_TOTAL;
import static com.yahoo.bard.webservice.web.handlers.SplitQueryRequestHandler.SPLITS;
import static com.yahoo.bard.webservice.web.handlers.SplitQueryRequestHandler.SPLIT_QUERIES;

import com.yahoo.bard.webservice.application.healthchecks.AllDimensionsLoadedHealthCheck;
import com.yahoo.bard.webservice.application.healthchecks.DataSourceMetadataLoaderHealthCheck;
import com.yahoo.bard.webservice.application.healthchecks.DruidDimensionsLoaderHealthCheck;
import com.yahoo.bard.webservice.application.healthchecks.SegmentMetadataLoaderHealthCheck;
import com.yahoo.bard.webservice.application.healthchecks.VersionHealthCheck;
import com.yahoo.bard.webservice.config.BardFeatureFlag;
import com.yahoo.bard.webservice.config.FeatureFlag;
import com.yahoo.bard.webservice.config.FeatureFlagRegistry;
import com.yahoo.bard.webservice.config.SystemConfig;
import com.yahoo.bard.webservice.config.SystemConfigProvider;
import com.yahoo.bard.webservice.data.DruidQueryBuilder;
import com.yahoo.bard.webservice.data.DruidResponseParser;
import com.yahoo.bard.webservice.data.PartialDataHandler;
import com.yahoo.bard.webservice.data.cache.DataCache;
import com.yahoo.bard.webservice.data.cache.HashDataCache;
import com.yahoo.bard.webservice.data.cache.MemDataCache;
import com.yahoo.bard.webservice.data.cache.MemTupleDataCache;
import com.yahoo.bard.webservice.data.cache.StubDataCache;
import com.yahoo.bard.webservice.data.config.ConfigurationLoader;
import com.yahoo.bard.webservice.data.config.ResourceDictionaries;
import com.yahoo.bard.webservice.data.config.dimension.DimensionConfig;
import com.yahoo.bard.webservice.data.config.dimension.DimensionLoader;
import com.yahoo.bard.webservice.data.config.dimension.KeyValueStoreDimensionLoader;
import com.yahoo.bard.webservice.data.config.metric.MetricLoader;
import com.yahoo.bard.webservice.data.config.table.TableLoader;
import com.yahoo.bard.webservice.data.dimension.DimensionDictionary;
import com.yahoo.bard.webservice.data.filterbuilders.DefaultDruidFilterBuilder;
import com.yahoo.bard.webservice.data.filterbuilders.DruidFilterBuilder;
import com.yahoo.bard.webservice.data.metric.MetricDictionary;
import com.yahoo.bard.webservice.data.metric.TemplateDruidQueryMerger;
import com.yahoo.bard.webservice.data.time.StandardTimeContext;
import com.yahoo.bard.webservice.data.time.TimeContext;
import com.yahoo.bard.webservice.druid.client.DruidClientConfigHelper;
import com.yahoo.bard.webservice.druid.client.DruidServiceConfig;
import com.yahoo.bard.webservice.druid.client.DruidWebService;
import com.yahoo.bard.webservice.druid.client.impl.AsyncDruidWebServiceImpl;
import com.yahoo.bard.webservice.druid.model.query.LookbackQuery;
import com.yahoo.bard.webservice.druid.util.FieldConverterSupplier;
import com.yahoo.bard.webservice.druid.util.FieldConverters;
import com.yahoo.bard.webservice.druid.util.SketchFieldConverter;
import com.yahoo.bard.webservice.metadata.DataSourceMetadataLoader;
import com.yahoo.bard.webservice.metadata.DataSourceMetadataService;
import com.yahoo.bard.webservice.metadata.QuerySigningService;
import com.yahoo.bard.webservice.metadata.RequestedIntervalsFunction;
import com.yahoo.bard.webservice.metadata.SegmentIntervalsHashIdGenerator;
import com.yahoo.bard.webservice.metadata.SegmentMetadataLoader;
import com.yahoo.bard.webservice.table.LogicalTableDictionary;
import com.yahoo.bard.webservice.table.PhysicalTableDictionary;
import com.yahoo.bard.webservice.table.resolver.DefaultPhysicalTableResolver;
import com.yahoo.bard.webservice.table.resolver.PhysicalTableResolver;
import com.yahoo.bard.webservice.util.DefaultingDictionary;
import com.yahoo.bard.webservice.util.SimplifiedIntervalList;
import com.yahoo.bard.webservice.web.DataApiRequest;
import com.yahoo.bard.webservice.web.DimensionApiRequestMapper;
import com.yahoo.bard.webservice.web.DimensionsApiRequest;
import com.yahoo.bard.webservice.web.FilteredSketchMetricsHelper;
import com.yahoo.bard.webservice.web.MetricsApiRequest;
import com.yahoo.bard.webservice.web.MetricsFilterSetBuilder;
import com.yahoo.bard.webservice.web.NoOpRequestMapper;
import com.yahoo.bard.webservice.web.RequestMapper;
import com.yahoo.bard.webservice.web.SlicesApiRequest;
import com.yahoo.bard.webservice.web.TablesApiRequest;
import com.yahoo.bard.webservice.web.handlers.workflow.DruidWorkflow;
import com.yahoo.bard.webservice.web.handlers.workflow.RequestWorkflowProvider;
import com.yahoo.bard.webservice.web.util.QueryWeightUtil;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.RatioGauge;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.glassfish.hk2.utilities.Binder;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.constraints.NotNull;

/**
 *   Abstract Binder factory implements the standard buildBinder functionality.
 *   It is left to individual projects to subclass, providing dimensions config,
 *   Metric and Table loading classes.
 */
public abstract class AbstractBinderFactory implements BinderFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractBinderFactory.class);
    private static final SystemConfig SYSTEM_CONFIG = SystemConfigProvider.getInstance();

    public static final String HEALTH_CHECK_NAME_SEGMENT_METADATA = "segment metadata loader";
    public static final String HEALTH_CHECK_NAME_DATASOURCE_METADATA = "datasource metadata loader";
    public static final String HEALTH_CHECK_NAME_DRUID_DIM_LOADER = "druid dimensions loader";
    public static final String HEALTH_CHECK_VERSION = "version";
    public static final String HEALTH_CHECK_NAME_DIMENSION = "dimension check";

    private static final String METER_CACHE_HIT_RATIO = "queries.meter.cache.hit_ratio";
    private static final String METER_SPLITS_TOTAL_RATIO = "queries.meter.split_queries.total_ratio";
    private static final String METER_SPLITS_RATIO = "queries.meter.split_queries.ratio";

    // Two minutes in milliseconds
    public static final int HC_LAST_RUN_PERIOD_MILLIS_DEFAULT = 120 * 1000;
    public static final int LOADER_SCHEDULER_THREAD_POOL_SIZE_DEFAULT = 4;

    public static final int SEG_LOADER_HC_LAST_RUN_PERIOD_MILLIS = SYSTEM_CONFIG.getIntProperty(
            SYSTEM_CONFIG.getPackageVariableName("seg_loader_health_check_last_run_duration"),
            HC_LAST_RUN_PERIOD_MILLIS_DEFAULT
    );

    public static final int DRUID_DIM_LOADER_HC_LAST_RUN_PERIOD_MILLIS = SYSTEM_CONFIG.getIntProperty(
            SYSTEM_CONFIG.getPackageVariableName("druid_dim_loader_health_check_last_run_duration"),
            HC_LAST_RUN_PERIOD_MILLIS_DEFAULT
    );

    public static final int LOADER_SCHEDULER_THREAD_POOL_SIZE = SYSTEM_CONFIG.getIntProperty(
            SYSTEM_CONFIG.getPackageVariableName("loader_scheduler_thread_pool_size"),
            LOADER_SCHEDULER_THREAD_POOL_SIZE_DEFAULT
    );

    private ObjectMappersSuite objectMappers;

    private final TaskScheduler loaderScheduler = new TaskScheduler(LOADER_SCHEDULER_THREAD_POOL_SIZE);

    public AbstractBinderFactory() {
        super();
    }

    @Override
    public final Binder buildBinder() {
        return new AbstractBinder() {
            @Override
            protected void configure() {
                HealthCheckRegistry healthCheckRegistry = HealthCheckRegistryFactory.getRegistry();

                Stream.concat(
                        collectFeatureFlags(BardFeatureFlag.class).stream(),
                        collectFeatureFlags().stream()
                ).forEach(flag -> bind(flag).named(flag.name()).to(FeatureFlag.class));

                bind(FeatureFlagRegistry.class).to(FeatureFlagRegistry.class);

                // Bard currently expects there to be one web service optimized for low latency queries
                DruidWebService uiDruidWebService = buildUiDruidWebService(getMappers().getMapper());

                // As well as one for open ended, potentially long running queries
                DruidWebService nonUiDruidWebService = buildNonUiDruidWebService(getMappers().getMapper());

                bind(uiDruidWebService).named("uiDruidWebService").to(DruidWebService.class);
                bind(nonUiDruidWebService).named("nonUiDruidWebService").to(DruidWebService.class);

                // A separate web service for metadata
                DruidWebService metadataDruidWebService = null;
                if (DRUID_COORDINATOR_METADATA.isOn()) {
                    metadataDruidWebService = buildMetadataDruidWebService(getMappers().getMapper());
                    bind(metadataDruidWebService).named("metadataDruidWebService").to(DruidWebService.class);
                }

                // Bind the timeGrain provider
                bind(getTimeContext()).to(TimeContext.class);

                // Bind the request building action classes for druid
                bind(DruidQueryBuilder.class).to(DruidQueryBuilder.class);
                bind(TemplateDruidQueryMerger.class).to(TemplateDruidQueryMerger.class);
                bind(DruidResponseParser.class).to(DruidResponseParser.class);
                bind(buildDruidFilterBuilder()).to(DruidFilterBuilder.class);

                //Initialize the field converter
                FieldConverterSupplier.sketchConverter = initializeSketchConverter();

                //Initialize the metrics filter helper
                FieldConverterSupplier.metricsFilterSetBuilder =  initializeMetricsFilterSetBuilder();

                // Build the configuration loader and load configuration
                ConfigurationLoader loader = buildConfigurationLoader();
                loader.load();

                // Bind the configuration dictionaries
                bind(loader.getDimensionDictionary()).to(DimensionDictionary.class);
                bind(loader.getMetricDictionary()).to(MetricDictionary.class);
                bind(loader.getLogicalTableDictionary()).to(LogicalTableDictionary.class);
                bind(loader.getPhysicalTableDictionary()).to(PhysicalTableDictionary.class);

                // Bind the request mappers
                Map<String, RequestMapper> requestMappers = getRequestMappers(loader.getDictionaries());
                bind(requestMappers.getOrDefault(DimensionsApiRequest.REQUEST_MAPPER_NAMESPACE,
                        new DimensionApiRequestMapper(loader.getDictionaries())))
                        .named(DimensionsApiRequest.REQUEST_MAPPER_NAMESPACE).to(RequestMapper.class);
                bind(requestMappers.getOrDefault(MetricsApiRequest.REQUEST_MAPPER_NAMESPACE,
                        new NoOpRequestMapper(loader.getDictionaries())))
                        .named(MetricsApiRequest.REQUEST_MAPPER_NAMESPACE).to(RequestMapper.class);
                bind(requestMappers.getOrDefault(SlicesApiRequest.REQUEST_MAPPER_NAMESPACE,
                        new NoOpRequestMapper(loader.getDictionaries())))
                        .named(SlicesApiRequest.REQUEST_MAPPER_NAMESPACE).to(RequestMapper.class);
                bind(requestMappers.getOrDefault(TablesApiRequest.REQUEST_MAPPER_NAMESPACE,
                        new NoOpRequestMapper(loader.getDictionaries())))
                        .named(TablesApiRequest.REQUEST_MAPPER_NAMESPACE).to(RequestMapper.class);
                bind(requestMappers.getOrDefault(DataApiRequest.REQUEST_MAPPER_NAMESPACE,
                        new NoOpRequestMapper(loader.getDictionaries())))
                        .named(DataApiRequest.REQUEST_MAPPER_NAMESPACE).to(RequestMapper.class);

                // Setup end points and back end services
                setupHealthChecks(healthCheckRegistry, loader.getDimensionDictionary());
                setupGauges();

                bind(buildCache()).to(DataCache.class);
                bind(QueryWeightUtil.class).to(QueryWeightUtil.class);

                bind(getMappers()).to(ObjectMappersSuite.class);
                bind(getMappers().getMapper()).to(ObjectMapper.class);

                bind(getWorkflow()).to(RequestWorkflowProvider.class);
                bind(getPhysicalTableResolver()).to(PhysicalTableResolver.class);
                bind(PartialDataHandler.class).to(PartialDataHandler.class);

                DataSourceMetadataService dataSourceMetadataService = buildDataSourceMetadataService();

                QuerySigningService<?> querySigningService = buildQuerySigningService(
                        loader.getPhysicalTableDictionary(),
                        dataSourceMetadataService
                );

                SegmentMetadataLoader segmentMetadataLoader = buildSegmentMetadataLoader(
                        uiDruidWebService,
                        loader.getPhysicalTableDictionary(),
                        loader.getDimensionDictionary(),
                        getMappers().getMapper()
                );
                setupPartialData(healthCheckRegistry, segmentMetadataLoader);

                if (DRUID_COORDINATOR_METADATA.isOn()) {
                    DataSourceMetadataLoader dataSourceMetadataLoader = buildDataSourceMetadataLoader(
                            metadataDruidWebService,
                            loader.getPhysicalTableDictionary(),
                            dataSourceMetadataService,
                            getMappers().getMapper()
                    );

                    setupDataSourceMetaData(healthCheckRegistry, dataSourceMetadataLoader);
                }
                bind(querySigningService).to(QuerySigningService.class);

                if (DRUID_DIMENSIONS_LOADER.isOn()) {
                    DruidDimensionsLoader druidDimensionsLoader = buildDruidDimensionsLoader(
                            nonUiDruidWebService,
                            loader.getPhysicalTableDictionary(),
                            loader.getDimensionDictionary()
                    );
                    setupDruidDimensionsLoader(healthCheckRegistry, druidDimensionsLoader);
                }

                // Call post-binding hook to allow for additional binding
                afterBinding(this);
            }
        };
    }

    /**
     * Initialize the field converter.
     * By default it is SketchFieldConverter
     *
     * @return An instance of SketchFieldConverter
     */
    protected FieldConverters initializeSketchConverter() {
        return new SketchFieldConverter();
    }

    /**
     * Initialize the FilteredMetricsHelper. By default it is FilteredSketchMetricsHelper
     *
     * @return An instance of FilteredSketchMetricsHelper
     */
    protected MetricsFilterSetBuilder initializeMetricsFilterSetBuilder() {
        return new FilteredSketchMetricsHelper();
    }

    /**
     * Build an ObjectMappersSuite for everyone to use, since they are heavy-weight.
     *
     * @return The instance of ObjectMappersSuite
     */
    protected final ObjectMappersSuite getMappers() {
        if (objectMappers == null) {
            objectMappers = new ObjectMappersSuite();
        }
        return objectMappers;
    }

    /**
     * Return the instance of ObjectMapper defined in the current ObjectMappersSuite.
     *
     * @return The instance of ObjectMapper
     */
    protected final ObjectMapper getMapper() {
        return getMappers().getMapper();
    }

    /**
     * Register required core health checks.
     *
     * @param healthCheckRegistry  The health check registry
     * @param dimensionDictionary  The container with all dimensions
     */
    protected final void setupHealthChecks(
            HealthCheckRegistry healthCheckRegistry,
            DimensionDictionary dimensionDictionary
    ) {
        // Health checks are registered here since they should be configured at startup.
        HealthCheck allDimsLoaded = new AllDimensionsLoadedHealthCheck(dimensionDictionary);
        healthCheckRegistry.register(HEALTH_CHECK_NAME_DIMENSION, allDimsLoaded);
        healthCheckRegistry.register(HEALTH_CHECK_VERSION, new VersionHealthCheck());
    }

    /**
     * Register global gauges.
     */
    protected final void setupGauges() {
        // Gauges are registered here since they should be configured only once at startup.
        MetricRegistry metricRegistry = MetricRegistryFactory.getRegistry();
        Map<String, Metric> metrics = metricRegistry.getMetrics();

        if (!metrics.containsKey(METER_CACHE_HIT_RATIO)) {
            metricRegistry.register(
                    METER_CACHE_HIT_RATIO,
                    new RatioGauge() {
                        @Override
                        protected Ratio getRatio() {
                            return CACHE_REQUESTS.getCount() != 0
                                    ? Ratio.of(CACHE_HITS.getCount(), CACHE_REQUESTS.getCount())
                                    : Ratio.of(0, 1);
                        }
                    }
            );
        }

        if (!metrics.containsKey(METER_SPLITS_TOTAL_RATIO)) {
            metricRegistry.register(
                    METER_SPLITS_TOTAL_RATIO,
                    new RatioGauge() {
                        @Override
                        protected Ratio getRatio() {
                            return QUERY_REQUEST_TOTAL.getCount() != 0
                                    ? Ratio.of(SPLIT_QUERIES.getCount(), QUERY_REQUEST_TOTAL.getCount())
                                    : Ratio.of(0, 1);
                        }
                    }
            );
        }

        if (!metrics.containsKey(METER_SPLITS_RATIO)) {
            metricRegistry.register(
                    METER_SPLITS_RATIO,
                    new RatioGauge() {
                        @Override
                        protected Ratio getRatio() {
                            return SPLITS.getCount() != 0
                                    ? Ratio.of(SPLIT_QUERIES.getCount(), SPLITS.getCount())
                                    : Ratio.of(0, 1);
                        }
                    }
            );
        }
    }

    /**
     * Creates an object that constructs Druid dimension filters from Bard dimension filters.
     * Constructs a {@link DefaultDruidFilterBuilder} by default.
     *
     * @return An object to build Druid filters from API filters
     */
    protected DruidFilterBuilder buildDruidFilterBuilder() {
        return new DefaultDruidFilterBuilder();
    }

    /**
     * Create a service to store the datasource metadata.
     *
     * @return A datasource metadata service
     */
    protected DataSourceMetadataService buildDataSourceMetadataService() {
        return new DataSourceMetadataService();
    }

    /**
     * Build a QuerySigningService.
     *
     * @param physicalTableDictionary  the dictionary of physical tables
     * @param dataSourceMetadataService  A datasource metadata service
     *
     * @return A QuerySigningService
     */
    protected QuerySigningService<?> buildQuerySigningService(
            PhysicalTableDictionary physicalTableDictionary,
            DataSourceMetadataService dataSourceMetadataService
    ) {
        return new SegmentIntervalsHashIdGenerator(
                physicalTableDictionary,
                dataSourceMetadataService,
                buildSigningFunctions()
        );
    }

    /**
     * Build a Map of Class to Function that should be used to get requestedIntervals from the DruidQuery.
     *
     * @return A Map that maps Class to a function that computes the requested intervals for a Druid query of
     * that particular Class
     */
    protected Map<Class, RequestedIntervalsFunction> buildSigningFunctions() {

        DefaultingDictionary<Class, RequestedIntervalsFunction> signingFunctions = new DefaultingDictionary<>(
                druidAggregationQuery -> new SimplifiedIntervalList(druidAggregationQuery.getIntervals())
        );

        signingFunctions.put(LookbackQuery.class, new LookbackQuery.LookbackQueryRequestedIntervalsFunction());

        return signingFunctions;
    }

    /**
     * Build a segment metadata loader.
     *
     * @param webService  The web service used by the timer to query partial data
     * @param physicalTableDictionary  The table to update dimensions on
     * @param dimensionDictionary  The dimensions to update
     * @param mapper  The object mapper to process segment metadata json
     *
     * @return A segment metadata loader
     *
     * @deprecated The http endpoints in Druid that this service relies on have been deprecated
     */
    @Deprecated
    protected SegmentMetadataLoader buildSegmentMetadataLoader(
            DruidWebService webService,
            PhysicalTableDictionary physicalTableDictionary,
            DimensionDictionary dimensionDictionary,
            ObjectMapper mapper
    ) {
        return new SegmentMetadataLoader(
                physicalTableDictionary,
                dimensionDictionary,
                webService,
                mapper
        );
    }

    /**
     * Build a datasource metadata loader.
     *
     * @param webService  The web service used by the loader to query druid for segments availability.
     * @param physicalTableDictionary  The table to get the dimensions from.
     * @param metadataService  The service to be used to store the datasource metadata.
     * @param mapper  The object mapper to process the metadata json.
     *
     * @return A datasource metadata loader.
     */
    protected DataSourceMetadataLoader buildDataSourceMetadataLoader(
            DruidWebService webService,
            PhysicalTableDictionary physicalTableDictionary,
            DataSourceMetadataService metadataService,
            ObjectMapper mapper
    ) {
        return new DataSourceMetadataLoader(
                physicalTableDictionary,
                metadataService,
                webService,
                mapper
        );
    }

    /**
     * Build a DruidDimensionsLoader.
     *
     * @param webService  The web service used by the loader to query dimension values
     * @param physicalTableDictionary  The table to update dimensions on
     * @param dimensionDictionary  The dimensions to update
     *
     * @return A DruidDimensionsLoader
     */
    protected DruidDimensionsLoader buildDruidDimensionsLoader(
            DruidWebService webService,
            PhysicalTableDictionary physicalTableDictionary,
            DimensionDictionary dimensionDictionary
    ) {
        return new DruidDimensionsLoader(
                physicalTableDictionary,
                dimensionDictionary,
                webService
        );
    }

    /**
     * Schedule a segment metadata loader and register its health check.
     *
     * @param healthCheckRegistry  The health check registry to register partial data health checks
     * @param segmentMetadataLoader  The segment metadata loader to use for partial data monitoring and health checks
     */
    protected final void setupPartialData(
            HealthCheckRegistry healthCheckRegistry,
            SegmentMetadataLoader segmentMetadataLoader
    ) {
        scheduleLoader(segmentMetadataLoader);

        // Register Segment metadata loader health check
        HealthCheck segMetaLoaderHealthCheck = new SegmentMetadataLoaderHealthCheck(
                segmentMetadataLoader,
                SEG_LOADER_HC_LAST_RUN_PERIOD_MILLIS
        );
        healthCheckRegistry.register(HEALTH_CHECK_NAME_SEGMENT_METADATA, segMetaLoaderHealthCheck);
    }

    /**
     * Schedule a datasource metadata loader and register its health check.
     *
     * @param healthCheckRegistry  The health check registry to register partial data health checks.
     * @param dataSourceMetadataLoader  The datasource metadata loader to use.
     */
    protected final void setupDataSourceMetaData(
            HealthCheckRegistry healthCheckRegistry,
            DataSourceMetadataLoader dataSourceMetadataLoader
    ) {
        scheduleLoader(dataSourceMetadataLoader);

        // Register Segment metadata loader health check
        HealthCheck dataSourceMetadataLoaderHealthCheck = new DataSourceMetadataLoaderHealthCheck(
                dataSourceMetadataLoader,
                SEG_LOADER_HC_LAST_RUN_PERIOD_MILLIS
        );
        healthCheckRegistry.register(HEALTH_CHECK_NAME_DATASOURCE_METADATA, dataSourceMetadataLoaderHealthCheck);
    }

    /**
     * Schedule DruidDimensionsLoader and register its health check.
     *
     * @param healthCheckRegistry  The health check registry to register Dimension lookup health checks
     * @param dataDruidDimensionsLoader  The DruidDimensionLoader used for monitoring and health checks
     */
    protected final void setupDruidDimensionsLoader(
            HealthCheckRegistry healthCheckRegistry,
            DruidDimensionsLoader dataDruidDimensionsLoader
    ) {
        scheduleLoader(dataDruidDimensionsLoader);

        // Register DruidDimensionsLoader health check
        HealthCheck druidDimensionsLoaderHealthCheck = new DruidDimensionsLoaderHealthCheck(
                dataDruidDimensionsLoader,
                DRUID_DIM_LOADER_HC_LAST_RUN_PERIOD_MILLIS
        );
        healthCheckRegistry.register(HEALTH_CHECK_NAME_DRUID_DIM_LOADER, druidDimensionsLoaderHealthCheck);
    }

    /**
     * Return a workflow class to bind to RequestWorkflowProvider.
     *
     * @return a workflow class
     */
    protected Class<? extends RequestWorkflowProvider> getWorkflow() {
        return DruidWorkflow.class;
    }

    /**
     * Build a cache for data requests and matching responses.
     *
     * @return The cache instance
     */
    protected DataCache<?> buildCache() {
        if (BardFeatureFlag.DRUID_CACHE_V2.isOn()) {
            try {
                MemTupleDataCache<String> cache = new MemTupleDataCache<>();
                LOG.info("MemcachedClient Version 2 started {}", cache);
                return cache;
            } catch (IOException e) {
                LOG.error("MemcachedClient Version 2 failed to start {}", e);
                throw new IllegalStateException(e);
            }
        } else if (BardFeatureFlag.DRUID_CACHE.isOn()) {
            try {
                DataCache<String> cache = new HashDataCache<>(new MemDataCache<HashDataCache.Pair<String, String>>());
                LOG.info("MemcachedClient started {}", cache);
                return cache;
            } catch (IOException e) {
                LOG.error("MemcachedClient failed to start {}", e);
                throw new IllegalStateException(e);
            }
        } else {
            // not used, but Jersey required a binding
            return new StubDataCache<>();
        }
    }

    /**
     * Asks for the valid feature flags that are expected to be defined in the system.
     * This method is also provided as an extension point for classes that need to add their own feature flags.
     * Such an extension is made easy when one of the final version of the method is used. For example:
     * <pre><code>
     * {@literal @}Override protected {@literal List<FeatureFlag>} collectFeatureFlags() {
     *      return collectFeatureFlags(AdditionalFeatureFlags.class);
     * }
     * </code></pre>
     *
     * @return The list of valid feature flags
     */
    protected List<FeatureFlag> collectFeatureFlags() {
        return Collections.<FeatureFlag>emptyList();
    }

    /**
     * Given specific enumeration classes containing feature flags it returns a list with all the valid enumerations.
     *
     * @param enumerations  The enumeration classes that define feature flags
     *
     * @return The list of valid feature flags
     */
    protected final List<FeatureFlag> collectFeatureFlags(Class<? extends FeatureFlag>... enumerations) {
        return collectFeatureFlags(Arrays.asList(enumerations));
    }

    /**
     * Given a list of enumeration classes containing feature flags it returns a list with all the valid enumerations.
     *
     * @param enumerations  A list with the enumeration classes that define feature flags
     *
     * @return A list with all the defined feature flags
     */
    protected final List<FeatureFlag> collectFeatureFlags(List<Class<? extends FeatureFlag>> enumerations) {
        return enumerations.stream()
                .map(it -> it.getEnumConstants())
                .flatMap(it -> Arrays.<FeatureFlag>stream(it))
                .collect(Collectors.toList());
    }

    /**
     * Build an application specific configuration loader initialized with pluggable loaders.
     *
     * @return A configuration loader instance
     */
    protected final ConfigurationLoader buildConfigurationLoader() {
        DimensionLoader dimensionLoader = getDimensionLoader();
        TableLoader tableLoader = getTableLoader();
        MetricLoader metricLoader = getMetricLoader();
        return buildConfigurationLoader(dimensionLoader, metricLoader, tableLoader);
    }

    /**
     * Extension point for building custom Configuration Loaders.
     *
     * @param dimensionLoader  A dimension loader
     * @param metricLoader  A metric loader
     * @param tableLoader  A table loader
     *
     * @return A configurationLoader instance
     */
    protected ConfigurationLoader buildConfigurationLoader(
            DimensionLoader dimensionLoader,
            MetricLoader metricLoader,
            TableLoader tableLoader
    ) {
        return new ConfigurationLoader(dimensionLoader, metricLoader, tableLoader);
    }

    /**
     * Create a Dimension Loader which will populate the dimensionDictionary.
     * The default Dimension Loader builds KeyValueStore Dimensions which are configured via the
     * {@link #getDimensionConfigurations()} method.
     *
     * @return a Dimension Loader instance
     */
    protected DimensionLoader getDimensionLoader() {
        return new KeyValueStoreDimensionLoader(getDimensionConfigurations());
    }

    /**
     * Create a Metric Loader.
     * <p>
     * Metric loader populates the metricDictionary
     *
     * @return a metric loader instance
     */
    protected abstract MetricLoader getMetricLoader();

    /**
     * A set of all dimension configurations for this application.
     * <p>
     * These dimension configurations will be used to build the dimensions dictionary
     *
     * @return A set of configuration objects describing dimensions
     */
    protected abstract Set<DimensionConfig> getDimensionConfigurations();


    /**
     * Extension point for selecting TimeContext implementations.
     *
     * @return A time context implementation
     */
    protected Class<? extends TimeContext> getTimeContext() {
        return StandardTimeContext.class;
    }

    /**
     * Select a physical table resolver class.
     *
     * @return A table resolver implementation
     */
    protected Class<? extends PhysicalTableResolver> getPhysicalTableResolver() {
        return DefaultPhysicalTableResolver.class;
    }

    /**
     * Create a Table Loader.
     * <p>
     * Table loader populates the physicalTableDictionary and logicalTableDictionary
     *
     * @return A table loader instance
     */
    protected abstract TableLoader getTableLoader();

    /**
     * Get a map of named RequestMappers.
     * <p>
     * Create an empty map by default
     *
     * @param resourceDictionaries  the ResourceDictionaries used for constructing RequestMappers
     *
     * @return A map where the key is the name of the requestMapper for binding, and value is the requestMapper
     */
    protected @NotNull Map<String, RequestMapper> getRequestMappers(ResourceDictionaries resourceDictionaries) {
        return new HashMap<>(0);
    }

    /**
     * Create a DruidWebService.
     * <p>
     * Provided so subclasses can implement alternative druid web service implementations
     *
     * @param druidServiceConfig  Configuration for the Druid Service
     * @param mapper shared instance of {@link com.fasterxml.jackson.databind.ObjectMapper}
     *
     * @return A DruidWebService
     */
    protected DruidWebService buildDruidWebService(DruidServiceConfig druidServiceConfig, ObjectMapper mapper) {
        return new AsyncDruidWebServiceImpl(druidServiceConfig, mapper);
    }

    /**
     * Create a DruidWebService for the UI connection.
     * <p>
     * Provided so subclasses can implement alternative druid web service implementations for the UI connection
     *
     * @param mapper shared instance of {@link com.fasterxml.jackson.databind.ObjectMapper}
     *
     * @return A DruidWebService
     */
    protected DruidWebService buildUiDruidWebService(ObjectMapper mapper) {
        return buildDruidWebService(DruidClientConfigHelper.getUiServiceConfig(), mapper);
    }

    /**
     * Create a DruidWebService for the non-UI connection.
     * <p>
     * Provided so subclasses can implement alternative druid web service implementations for the non-UI connection
     *
     * @param mapper shared instance of {@link com.fasterxml.jackson.databind.ObjectMapper}
     *
     * @return A DruidWebService
     */
    protected DruidWebService buildNonUiDruidWebService(ObjectMapper mapper) {
        return buildDruidWebService(DruidClientConfigHelper.getNonUiServiceConfig(), mapper);
    }

    /**
     * Create a DruidWebService for metadata.
     *
     * @param mapper shared instance of {@link com.fasterxml.jackson.databind.ObjectMapper}
     *
     * @return A DruidWebService
     */
    protected DruidWebService buildMetadataDruidWebService(ObjectMapper mapper) {
        return buildDruidWebService(DruidClientConfigHelper.getMetadataServiceConfig(), mapper);
    }

    @Override
    public void afterRegistration(ResourceConfig resourceConfig) {
        // NoOp by default
    }

    /**
     * Allows additional app-specific binding.
     *
     * @param abstractBinder  Binder to use for binding
     */
    protected void afterBinding(AbstractBinder abstractBinder) {
        // NoOp by default
    }

    /**
     * Schedule a loader task.
     *
     * @param loader  The loader task to run.
     */
    protected void scheduleLoader(Loader<?> loader) {
        loader.setFuture(
                loader.isPeriodic ?
                loaderScheduler.scheduleAtFixedRate(
                        loader,
                        loader.getDefinedDelay(),
                        loader.getDefinedPeriod(),
                        TimeUnit.MILLISECONDS
                ) :
                loaderScheduler.schedule(loader, loader.getDefinedDelay(), TimeUnit.MILLISECONDS)
        );
    }

    /**
     * Shutdown the scheduler for loader tasks.
     */
    protected void shutdownLoaderScheduler() {
        loaderScheduler.shutdownNow();
    }

    /**
     * Make sure the scheduler for loader tasks shuts down when the resources of this class are released.
     *
     * @throws Throwable  An exception raised by this method.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            shutdownLoaderScheduler();
        } finally {
            super.finalize();
        }
    }
}
