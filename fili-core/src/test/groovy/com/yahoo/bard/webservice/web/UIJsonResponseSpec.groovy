// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE file distributed with this work for terms.
package com.yahoo.bard.webservice.web

import static com.yahoo.bard.webservice.data.time.DefaultTimeGrain.DAY
import static com.yahoo.bard.webservice.util.SimplifiedIntervalList.NO_INTERVALS

import com.yahoo.bard.webservice.application.ObjectMappersSuite
import com.yahoo.bard.webservice.data.Result
import com.yahoo.bard.webservice.data.ResultSet
import com.yahoo.bard.webservice.data.dimension.BardDimensionField
import com.yahoo.bard.webservice.data.dimension.Dimension
import com.yahoo.bard.webservice.data.dimension.DimensionColumn
import com.yahoo.bard.webservice.data.dimension.DimensionField
import com.yahoo.bard.webservice.data.dimension.DimensionRow
import com.yahoo.bard.webservice.data.dimension.MapStoreManager
import com.yahoo.bard.webservice.data.dimension.impl.KeyValueStoreDimension
import com.yahoo.bard.webservice.data.dimension.impl.ScanSearchProviderManager
import com.yahoo.bard.webservice.data.metric.LogicalMetric
import com.yahoo.bard.webservice.data.metric.MetricColumn
import com.yahoo.bard.webservice.data.metric.mappers.NoOpResultSetMapper
import com.yahoo.bard.webservice.table.Schema
import com.yahoo.bard.webservice.util.GroovyTestUtils
import com.yahoo.bard.webservice.util.Pagination
import com.yahoo.bard.webservice.util.SimplifiedIntervalList

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import spock.lang.Specification

class UIJsonResponseSpec extends Specification {
    private static final ObjectMappersSuite MAPPERS = new ObjectMappersSuite()

    Schema newSchema
    Map<DimensionColumn, DimensionRow> dimensionRows
    Map<MetricColumn, BigDecimal> metricValues
    DateTime timeStamp
    Map<Dimension, Set<DimensionField>> defaultDimensionFieldsToShow
    SimplifiedIntervalList volatileIntervals = []


    def setup() {
        // Default JodaTime zone to UTC
        DateTimeZone.setDefault(DateTimeZone.UTC)

        // Build a default timestamp
        timeStamp = new DateTime(10000)

        // Build a default schema
        newSchema = new Schema(DAY)

        LinkedHashSet<DimensionField> dimensionFields = new LinkedHashSet<>()
        dimensionFields.add(BardDimensionField.ID)
        dimensionFields.add(BardDimensionField.DESC)

        // Add a dimension and some metrics to the schema
        Dimension newDimension = new KeyValueStoreDimension(
                "gender",
                "druid-gender",
                "gender-description",
                dimensionFields,
                MapStoreManager.getInstance("gender"),
                ScanSearchProviderManager.getInstance("gender"),
                [] as Set
        )
        newDimension.setLastUpdated(timeStamp)
        DimensionColumn dimensionColumn = DimensionColumn.addNewDimensionColumn(newSchema, newDimension)
        MetricColumn metricColumn1 = MetricColumn.addNewMetricColumn(newSchema, "metricColumn1Name")
        MetricColumn metricColumn2 = MetricColumn.addNewMetricColumn(newSchema, "metricColumn2Name")

        // Build a default dimension row
        DimensionRow dimensionRow = BardDimensionField.makeDimensionRow(
                newDimension,
                "gender-one-id",
                "gender-one-desc"
        )
        dimensionRows = [(dimensionColumn): dimensionRow]

        // Build some default dimension values
        metricValues = [
                (metricColumn1): 1234567.1234,
                (metricColumn2): 1234567.1234
        ]

        defaultDimensionFieldsToShow = [
                (newDimension): dimensionFields
        ]
    }

    def "Get single row response"() {

        given: "A Result Set with one row"
        Result r1 = new Result(dimensionRows, metricValues, timeStamp)
        ResultSet resultSet = new ResultSet([r1], newSchema)

        and: "An API Request"
        DataApiRequest apiRequest = Mock(DataApiRequest)
        apiRequest.getLogicalMetrics() >> {
            return newSchema.getColumns(MetricColumn).collect {
                new LogicalMetric(null, new NoOpResultSetMapper(), it.getName(), null)
            }
        }

        apiRequest.getDimensionFields() >> defaultDimensionFieldsToShow

        and: "An expected json serialization"
        String expectedJSON = """{
            "rows":[{
                        "metricColumn1Name":1234567.1234,
                        "dateTime":"1970-01-01 00:00:10.000",
                        "gender|desc":"gender-one-desc",
                        "metricColumn2Name":1234567.1234,
                        "gender|id":"gender-one-id"
            }]
        }"""

        when: "get and serialize a JsonResponse"
        Response jro = new Response(
                resultSet,
                apiRequest,
                NO_INTERVALS,
                volatileIntervals,
                [:],
                (Pagination) null,
                MAPPERS
        )

        ByteArrayOutputStream os = new ByteArrayOutputStream()
        jro.write(os)

        String responseJSON = os.toString()

        then: "The serialized JsonResponse matches what we expect"
        GroovyTestUtils.compareJson(responseJSON, expectedJSON)
    }

    def "Get multiple rows response"() {

        given: "A Result Set with multiple rows"
        Result r1 = new Result(dimensionRows, metricValues, timeStamp)
        ResultSet resultSet = new ResultSet([r1, r1, r1], newSchema)

        and: "An API Request"
        DataApiRequest apiRequest = Mock(DataApiRequest)
        apiRequest.getLogicalMetrics() >> {
            return newSchema.getColumns(MetricColumn).collect {
                new LogicalMetric(null, new NoOpResultSetMapper(), it.getName(), null)
            }
        }

        apiRequest.getDimensionFields() >> defaultDimensionFieldsToShow

        and: "An expected json serialization"
        String expectedJSON = """{
            "rows":[
                {
                    "metricColumn1Name":1234567.1234,
                    "dateTime":"1970-01-01 00:00:10.000",
                    "gender|desc":"gender-one-desc",
                    "metricColumn2Name":1234567.1234,
                    "gender|id":"gender-one-id"
                },
                {
                    "metricColumn1Name":1234567.1234,
                    "dateTime":"1970-01-01 00:00:10.000",
                    "gender|desc":"gender-one-desc",
                    "metricColumn2Name":1234567.1234,
                    "gender|id":"gender-one-id"
                },
                {
                    "metricColumn1Name":1234567.1234,
                    "dateTime":"1970-01-01 00:00:10.000",
                    "gender|desc":"gender-one-desc",
                    "metricColumn2Name":1234567.1234,
                    "gender|id":"gender-one-id"
                }
            ]
        }"""

        when: "We get and serialize a JsonResponse for it"
        Response jro = new Response(
                resultSet,
                apiRequest,
                NO_INTERVALS,
                volatileIntervals,
                [:],
                (Pagination) null,
                MAPPERS
        )
        ByteArrayOutputStream os = new ByteArrayOutputStream()
        jro.write(os)

        String responseJSON = os.toString()
        then: "The serialized JsonResponse matches what we expect"
        GroovyTestUtils.compareJson(responseJSON, expectedJSON)
    }
}
