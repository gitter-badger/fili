// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE file distributed with this work for terms.
package com.yahoo.bard.webservice.data.config.metric.makers

import com.yahoo.bard.webservice.data.metric.LogicalMetric
import com.yahoo.bard.webservice.data.metric.MetricDictionary
import com.yahoo.bard.webservice.data.metric.TemplateDruidQuery
import com.yahoo.bard.webservice.data.metric.mappers.NoOpResultSetMapper
import com.yahoo.bard.webservice.data.metric.mappers.SketchRoundUpMapper
import com.yahoo.bard.webservice.druid.model.aggregation.Aggregation
import com.yahoo.bard.webservice.druid.model.aggregation.SketchMergeAggregation
import com.yahoo.bard.webservice.druid.model.postaggregation.FieldAccessorPostAggregation
import com.yahoo.bard.webservice.druid.model.postaggregation.PostAggregation
import com.yahoo.bard.webservice.druid.model.postaggregation.SketchEstimatePostAggregation
import com.yahoo.bard.webservice.druid.model.postaggregation.SketchSetOperationPostAggregation
import com.yahoo.bard.webservice.druid.model.postaggregation.SketchSetOperationPostAggFunction

import spock.lang.Specification

class SketchSetOperationMakerSpec extends Specification {

    private static final int SKETCH_SIZE = 16000
    private static final String METRIC_NAME = "all users"
    private static final SketchSetOperationPostAggFunction SET_FUNCTION = SketchSetOperationPostAggFunction.UNION

    def """A sketch set operation metric is built correctly when the dependent metrics have both an aggregation
             and a post aggregation."""() {
        given: "The two logical metrics the built metric will depend on."
        Aggregation allYahoos = new SketchMergeAggregation("all_yahoos", "yahoos", SKETCH_SIZE)
        Aggregation allNonYahoos = new SketchMergeAggregation("all_nonyahoos", "nonyahoos", SKETCH_SIZE)
        PostAggregation accessYahoos = new FieldAccessorPostAggregation(allYahoos)
        PostAggregation accessNonYahoos = new FieldAccessorPostAggregation(allNonYahoos)
        PostAggregation userNumber = new SketchEstimatePostAggregation(
                METRIC_NAME,
                new SketchSetOperationPostAggregation(METRIC_NAME, SET_FUNCTION, accessYahoos, accessNonYahoos)
        )

        LogicalMetric firstMetric = new LogicalMetric(
                new TemplateDruidQuery([allYahoos] as Set, [] as Set),
                new NoOpResultSetMapper(),
                "all_yahoos",
                "All users from Yahoo"
        )
        LogicalMetric secondMetric = new LogicalMetric(
                new TemplateDruidQuery([allNonYahoos] as Set, [] as Set),
                new NoOpResultSetMapper(),
                "all_nonyahoos",
                "All users not from Yahoo"
        )
        List<LogicalMetric> allUsers = [firstMetric, secondMetric]

        and: "the expected LogicalMetric"
        TemplateDruidQuery expectedQuery = new TemplateDruidQuery([allYahoos, allNonYahoos] as Set, [userNumber] as Set)
        LogicalMetric metric = new LogicalMetric(expectedQuery, new SketchRoundUpMapper(METRIC_NAME), METRIC_NAME)

        and: "the maker with populated metrics."
        MetricMaker maker = new SketchSetOperationMaker(new MetricDictionary(), SET_FUNCTION)
        allUsers.each { maker.metrics.add(it) }

        expect:
        maker.make(METRIC_NAME, allUsers*.getName()) == metric
    }
}
