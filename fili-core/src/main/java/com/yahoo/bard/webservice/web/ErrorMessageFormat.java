// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE file distributed with this work for terms.
package com.yahoo.bard.webservice.web;

import com.yahoo.bard.webservice.MessageFormatter;

//CHECKSTYLE:OFF
public enum ErrorMessageFormat implements MessageFormatter {

    TABLE_UNDEFINED("Table name '%s' does not exist."),
    TABLE_ALIGNMENT_UNDEFINED("Table '%s' cannot be aligned to a request with intervals: %s."),
    TABLE_SCHEMA_UNDEFINED("Table '%s' is incompatible with the dimensions '%s', metrics '%s' and granularity '%s' requested.",
            "Table '%s' is incompatible with the dimensions '%s', metrics '%s' and granularity '%s' requested."),

    EMPTY_DICTIONARY("%s Dictionary is empty."),

    DIMENSIONS_UNDEFINED("Dimension(s) '%s' do not exist."),
    DIMENSIONS_NOT_IN_TABLE("Requested dimension(s) '%s' are not supported by the table '%s'."),
    DIMENSION_FIELDS_UNDEFINED("Dimension field(s) '%s' do not exist for dimension '%s'"),

    SLICE_UNDEFINED("Slice name '%s' does not exist."),

    UNKNOWN_GRANULARITY("'%s' is not a valid granularity. Try 'day', 'week', 'month', 'year' or 'all'"),
    GRANULARITY_MERGE("'%s' time zone cannot be applied to time grain '%s'"),

    INVALID_GRANULARITY("Granularity %s is of an unexpected type %s."),
    TABLE_GRANULARITY_MISMATCH("Invalid pair of granularity '%s' and table '%s'."),

    TIME_ALIGNMENT("'%s' does not align with granularity '%s'.%s"),
    EMPTY_INTERVAL_FORMAT("Query has an interval of zero duration: %s"),

    QUERY_GRAIN_NOT_SATISFIED("Illegal request: query requires time grain '%s' which cannot satisfy request time grain '%s'."),

    INTERVAL_MISSING("Required parameter dateTime is missing. Use dateTime=YYYY-MM-DD/YYYY-MM-DD in the query string."),
    INTERVAL_INVALID("Interval '%s' is invalid. %s."),
    INVALID_INTERVAL_GRANULARITY("Invalid macro %s with the date interval %s"),
    INTERVAL_ZERO_LENGTH("Date time cannot have zero length intervals. %s."),

    INVALID_TIME_ZONE("Time zone '%s' is unknown.  See (http://joda-time.sourceforge.net/timezones.html) for valid time zone ids."),

    METRICS_MISSING("Required parameter metrics is missing or empty. Use 'metrics=METRICNAME1,METRICNAME2' in the query string."),
    METRICS_UNDEFINED("Metric(s) '%s' do not exist."),
    METRICS_NOT_IN_TABLE("Requested metric(s) '%s' are not supported by the table '%s'."),
    DUPLICATE_METRICS_IN_API_REQUEST(
            "Duplicate metric(s) are not allowed in the API request even if one is filtered and the other is unfiltered" +
            "Duplicate metric(s) are '%s'."),
    INCORRECT_METRIC_FILTER_FORMAT("The format of the metric filter is incorrect '%s'." +
            "The format should be like metrics=metric1,metric2(AND(dim1|id-in[value1,value2],dim2|id-in[value3,value4]))"),
    INVALID_METRIC_FILTER_CONDITION("Filter condition '%s' is not supported"),
    UNSUPPORTED_FILTERED_METRIC_CATEGORY
            ("Metric filtering is not supported for metric '%s' as it belongs to %s' category"),

    INVALID_NUMBER_OF_FIELDS("fields length shouldn't be more then one for Sketch Estimate operation %s"),

    SORT_DIRECTION_INVALID("Sort direction '%s' is invalid."),
    SORT_METRICS_NOT_IN_QUERY_FORMAT("Requested sort metric(s) '%s' were not selected in the metrics expression."),
    SORT_METRICS_NOT_SORTABLE_FORMAT("Sorting not possible on metric(s) '%s'."),
    SORT_METRICS_UNDEFINED("Metric(s) in sort expression '%s' do not exist."),

    ACCEPT_FORMAT_INVALID("Format '%s' is unknown. Choose from 'csv', 'json'."),

    FILTER_INVALID("Filter expression '%s' is invalid."),
    FILTER_ERROR("Filter expression '%s' resulted in the following error: %s."),
    FILTER_DIMENSION_UNDEFINED("Filter dimension '%s' does not exist."),
    FILTER_DIMENSION_NOT_IN_TABLE("Filter dimension '%s' is not supported by the table '%s'."),
    FILTER_FIELD_NOT_IN_DIMENSIONS("Filter dimension field '%s' is not supported by the dimension '%s'."),
    FILTER_OPERATOR_INVALID("Filter operator '%s' is invalid."),
    FILTER_SUBSTRING_OPERATIONS_DISABLED(
            "Filter operations 'startswith' and 'contains' are disabled for data requests.",
            "Filter operations 'startswith' and 'contains' are disabled for data requests. Enable by setting feature" +
            "flag: data_starts_with_contains_enabled"
    ),
    FILTER_DIMENSION_MISMATCH("Filter dimension %s does not match dimension %s."),


    INTEGER_INVALID("%s value:'%s' is invalid. Value must be a positive integer."),

    TOP_N_UNSORTED("Sort clause is missing: TopN requires at least one metric column as its sorting criterion"),

    PAGINATION_PARAMETER_MISSING("Missing parameter '%s.' Both 'perPage' and 'page' are required for pagination."),
    PAGINATION_PARAMETER_INVALID("Parameter '%s' expected a positive integer but received: '%s'"),
    PAGINATION_PAGE_INVALID("Requested page '%d' with '%d' rows per page, but there are only '%d' pages."),

    RESULT_SET_ERROR("Cannot build result set for query of type: %s."),
    TOO_MANY_PERIODS("Too many periods between the epoch %s and the target %s to calculate alignment on grain %s."),

    HAVING_INVALID("Having expression '%s' is invalid."),
    HAVING_NON_NUMERIC("Having expression '%s' is not numeric."),
    HAVING_ERROR("Having expression '%s' resulted in the following error: %s."),
    HAVING_METRIC_UNDEFINED("Having metric '%s' does not exist."),
    HAVING_METRIC_NOT_IN_TABLE("Having metric '%s' is not supported by the table '%s'."),
    HAVING_METRICS_NOT_IN_QUERY_FORMAT("Requested having metric(s) '%s' were not selected in the metrics expression."),
    HAVING_OPERATOR_INVALID("Having operator '%s' is invalid."),

    LOGINFO_CLASS_INVALID("Invalid LogInfo class: %s. Cannot define its order. Ignoring."),

    DRUID_METADATA_READ_ERROR("Unable to read metadata for: '%s'."),
    DRUID_METADATA_SEGMENTS_MISSING("No segment metadata available for tables: '%s'."),

    DRUID_URL_INVALID("Druid %s url is unset."),

    WEIGHT_CHECK_FAILED(
            "Result set too large. Try reducing interval, dimensions, or sketch metrics.",
            "The product of sketches and rows is too large: %d > %d"
    ),

    NON_AGGREGATABLE_INVALID("Query contains invalid use of the non-aggregatable dimensions: %s"),
    NO_TABLE_FOR_NON_AGGREGATABLE(
            "No table supports aggregation to exactly non-aggregatable dimensions: %s and aggregatable dimensions: %s"
    ),

    NO_PHYSICAL_TABLE_MATCHED("No matching physical table found for dimensions '%s', metrics '%s', and time grain '%s'"),

    DIMENSION_ROWS_NOT_FOUND("Dimension rows not found for %s with filter %s"),

    UNSUPPORTED_LOOKBACKQUERY_OPERATION("LookbackQuery creation failed for the requested metric",
            "withPostAggregations() is not supported by LookbackQuery. Try using withInnerQueryPostAggregations or withLookbackQueryPostAggregations");
    // CHECKSTYLE:ON

    private final String messageFormat;
    private final String loggingFormat;

    /**
     * An error message formatter with the same message for logging and messaging.
     *
     * @param messageFormat The format string for logging and messaging
     */
    ErrorMessageFormat(String messageFormat) {
        this(messageFormat, messageFormat);
    }

    /**
     * An error message formatter with different messages for logging and messaging
     *
     * @param messageFormat The format string for messaging
     * @param loggingFormat The format string for logging
     */
    ErrorMessageFormat(String messageFormat, String loggingFormat) {
        this.messageFormat = messageFormat;
        this.loggingFormat = loggingFormat;
    }

    @Override
    public String getMessageFormat() {
        return messageFormat;
    }

    @Override
    public String getLoggingFormat() {
        return loggingFormat;
    }
}
