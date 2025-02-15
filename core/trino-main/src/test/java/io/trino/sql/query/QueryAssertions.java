/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.query;

import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.spi.type.SqlTime;
import io.trino.spi.type.SqlTimeWithTimeZone;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlTimestampWithTimeZone;
import io.trino.spi.type.Type;
import io.trino.sql.planner.Plan;
import io.trino.sql.planner.assertions.PlanAssert;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.TableScanNode;
import io.trino.testing.LocalQueryRunner;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.AssertProvider;
import org.assertj.core.api.ListAssert;
import org.assertj.core.presentation.Representation;
import org.assertj.core.presentation.StandardRepresentation;
import org.intellij.lang.annotations.Language;

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;
import static io.trino.cost.StatsCalculator.noopStatsCalculator;
import static io.trino.sql.planner.assertions.PlanAssert.assertPlan;
import static io.trino.sql.query.QueryAssertions.ExpressionAssert.newExpressionAssert;
import static io.trino.sql.query.QueryAssertions.QueryAssert.newQueryAssert;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.transaction.TransactionBuilder.transaction;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class QueryAssertions
        implements Closeable
{
    private final QueryRunner runner;

    public QueryAssertions()
    {
        this(testSessionBuilder()
                .setCatalog("local")
                .setSchema("default")
                .build());
    }

    public QueryAssertions(Session session)
    {
        this(LocalQueryRunner.create(session));
    }

    public QueryAssertions(QueryRunner runner)
    {
        this.runner = requireNonNull(runner, "runner is null");
    }

    public Session.SessionBuilder sessionBuilder()
    {
        return Session.builder(runner.getDefaultSession());
    }

    public Session getDefaultSession()
    {
        return runner.getDefaultSession();
    }

    public AssertProvider<QueryAssert> query(@Language("SQL") String query)
    {
        return query(runner.getDefaultSession(), query);
    }

    public AssertProvider<QueryAssert> query(Session session, @Language("SQL") String query)
    {
        return newQueryAssert(query, runner, session);
    }

    public AssertProvider<ExpressionAssert> expression(@Language("SQL") String expression)
    {
        return expression(expression, runner.getDefaultSession());
    }

    public AssertProvider<ExpressionAssert> expression(@Language("SQL") String expression, Session session)
    {
        return newExpressionAssert(expression, runner, session);
    }

    public void assertQueryAndPlan(
            @Language("SQL") String actual,
            @Language("SQL") String expected,
            PlanMatchPattern pattern)
    {
        assertQuery(runner.getDefaultSession(), actual, expected, false);

        Plan plan = runner.executeWithPlan(runner.getDefaultSession(), actual, WarningCollector.NOOP).getQueryPlan();
        PlanAssert.assertPlan(runner.getDefaultSession(), runner.getMetadata(), runner.getStatsCalculator(), plan, pattern);
    }

    private void assertQuery(Session session, @Language("SQL") String actual, @Language("SQL") String expected, boolean ensureOrdering)
    {
        MaterializedResult actualResults = null;
        try {
            actualResults = execute(session, actual);
        }
        catch (RuntimeException ex) {
            fail("Execution of 'actual' query failed: " + actual, ex);
        }

        MaterializedResult expectedResults = null;
        try {
            expectedResults = execute(expected);
        }
        catch (RuntimeException ex) {
            fail("Execution of 'expected' query failed: " + expected, ex);
        }

        assertEquals(actualResults.getTypes(), expectedResults.getTypes(), "Types mismatch for query: \n " + actual + "\n:");

        List<MaterializedRow> actualRows = actualResults.getMaterializedRows();
        List<MaterializedRow> expectedRows = expectedResults.getMaterializedRows();

        if (ensureOrdering) {
            if (!actualRows.equals(expectedRows)) {
                assertEquals(actualRows, expectedRows, "For query: \n " + actual + "\n:");
            }
        }
        else {
            assertEqualsIgnoreOrder(actualRows, expectedRows, "For query: \n " + actual);
        }
    }

    public void assertQueryReturnsEmptyResult(@Language("SQL") String actual)
    {
        MaterializedResult actualResults = null;
        try {
            actualResults = execute(actual);
        }
        catch (RuntimeException ex) {
            fail("Execution of 'actual' query failed: " + actual, ex);
        }
        List<MaterializedRow> actualRows = actualResults.getMaterializedRows();
        assertEquals(actualRows.size(), 0);
    }

    public MaterializedResult execute(@Language("SQL") String query)
    {
        return execute(runner.getDefaultSession(), query);
    }

    public MaterializedResult execute(Session session, @Language("SQL") String query)
    {
        MaterializedResult actualResults;
        actualResults = runner.execute(session, query).toTestTypes();
        return actualResults;
    }

    @Override
    public void close()
    {
        runner.close();
    }

    public QueryRunner getQueryRunner()
    {
        return runner;
    }

    protected void executeExclusively(Runnable executionBlock)
    {
        runner.getExclusiveLock().lock();
        try {
            executionBlock.run();
        }
        finally {
            runner.getExclusiveLock().unlock();
        }
    }

    public static class QueryAssert
            extends AbstractAssert<QueryAssert, MaterializedResult>
    {
        private static final Representation ROWS_REPRESENTATION = new StandardRepresentation()
        {
            @Override
            public String toStringOf(Object object)
            {
                if (object instanceof List) {
                    List<?> list = (List<?>) object;
                    return list.stream()
                            .map(this::toStringOf)
                            .collect(Collectors.joining(", "));
                }
                if (object instanceof MaterializedRow) {
                    MaterializedRow row = (MaterializedRow) object;

                    return row.getFields().stream()
                            .map(this::formatRowElement)
                            .collect(Collectors.joining(", ", "(", ")"));
                }
                return super.toStringOf(object);
            }

            private String formatRowElement(Object value)
            {
                if (value == null) {
                    return "null";
                }
                if (value.getClass().isArray()) {
                    return formatArray(value);
                }
                // Using super.toStringOf would add quotes around String values, which could be expected for varchar values
                // but would be misleading for date/time values which come as String too. More proper formatting would need to be
                // type-aware.
                return String.valueOf(value);
            }
        };

        private final QueryRunner runner;
        private final Session session;
        private final String query;
        private boolean ordered;
        private boolean skipTypesCheck;
        private boolean skipResultsCorrectnessCheckForPushdown;

        static AssertProvider<QueryAssert> newQueryAssert(String query, QueryRunner runner, Session session)
        {
            MaterializedResult result = runner.execute(session, query);
            return () -> new QueryAssert(runner, session, query, result);
        }

        public QueryAssert(QueryRunner runner, Session session, String query, MaterializedResult actual)
        {
            super(actual, Object.class);
            this.runner = requireNonNull(runner, "runner is null");
            this.session = requireNonNull(session, "session is null");
            this.query = requireNonNull(query, "query is null");
        }

        public QueryAssert projected(int... columns)
        {
            return new QueryAssert(
                    runner,
                    session,
                    format("%s projected with %s", query, Arrays.toString(columns)),
                    new MaterializedResult(
                            actual.getMaterializedRows().stream()
                                    .map(row -> new MaterializedRow(
                                            row.getPrecision(),
                                            IntStream.of(columns)
                                                    .mapToObj(row::getField)
                                                    .collect(toList()))) // values are nullable
                                    .collect(toImmutableList()),
                            IntStream.of(columns)
                                    .mapToObj(actual.getTypes()::get)
                                    .collect(toImmutableList())));
        }

        public QueryAssert matches(BiFunction<Session, QueryRunner, MaterializedResult> evaluator)
        {
            MaterializedResult expected = evaluator.apply(session, runner);
            return matches(expected);
        }

        public QueryAssert ordered()
        {
            ordered = true;
            return this;
        }

        public QueryAssert skippingTypesCheck()
        {
            skipTypesCheck = true;
            return this;
        }

        public QueryAssert skipResultsCorrectnessCheckForPushdown()
        {
            skipResultsCorrectnessCheckForPushdown = true;
            return this;
        }

        public QueryAssert matches(@Language("SQL") String query)
        {
            MaterializedResult expected = runner.execute(session, query);
            return matches(expected);
        }

        public QueryAssert matches(MaterializedResult expected)
        {
            return satisfies(actual -> {
                if (!skipTypesCheck) {
                    assertTypes(actual, expected.getTypes());
                }

                ListAssert<MaterializedRow> assertion = assertThat(actual.getMaterializedRows())
                        .as("Rows")
                        .withRepresentation(ROWS_REPRESENTATION);

                if (ordered) {
                    assertion.containsExactlyElementsOf(expected.getMaterializedRows());
                }
                else {
                    assertion.containsExactlyInAnyOrderElementsOf(expected.getMaterializedRows());
                }
            });
        }

        public final QueryAssert matches(PlanMatchPattern expectedPlan)
        {
            transaction(runner.getTransactionManager(), runner.getAccessControl())
                    .execute(session, session -> {
                        Plan plan = runner.createPlan(session, query, WarningCollector.NOOP);
                        assertPlan(
                                session,
                                runner.getMetadata(),
                                noopStatsCalculator(),
                                plan,
                                expectedPlan);
                    });
            return this;
        }

        public QueryAssert containsAll(@Language("SQL") String query)
        {
            MaterializedResult expected = runner.execute(session, query);
            return containsAll(expected);
        }

        public QueryAssert containsAll(MaterializedResult expected)
        {
            return satisfies(actual -> {
                if (!skipTypesCheck) {
                    assertTypes(actual, expected.getTypes());
                }

                assertThat(actual.getMaterializedRows())
                        .as("Rows")
                        .withRepresentation(ROWS_REPRESENTATION)
                        .containsAll(expected.getMaterializedRows());
            });
        }

        public QueryAssert hasOutputTypes(List<Type> expectedTypes)
        {
            return satisfies(actual -> {
                assertTypes(actual, expectedTypes);
            });
        }

        public QueryAssert outputHasType(int index, Type expectedType)
        {
            return satisfies(actual -> {
                assertThat(actual.getTypes())
                        .as("Output types")
                        .element(index).isEqualTo(expectedType);
            });
        }

        private static void assertTypes(MaterializedResult actual, List<Type> expectedTypes)
        {
            assertThat(actual.getTypes())
                    .as("Output types")
                    .isEqualTo(expectedTypes);
        }

        public QueryAssert returnsEmptyResult()
        {
            return satisfies(actual -> {
                assertThat(actual.getMaterializedRows()).as("rows").isEmpty();
            });
        }

        /**
         * Verifies query is fully pushed down and verifies the results are the same as when the pushdown is disabled.
         */
        public QueryAssert isFullyPushedDown()
        {
            checkState(!(runner instanceof LocalQueryRunner), "isFullyPushedDown() currently does not work with LocalQueryRunner");

            transaction(runner.getTransactionManager(), runner.getAccessControl())
                    .execute(session, session -> {
                        Plan plan = runner.createPlan(session, query, WarningCollector.NOOP);
                        assertPlan(
                                session,
                                runner.getMetadata(),
                                noopStatsCalculator(),
                                plan,
                                PlanMatchPattern.output(
                                        PlanMatchPattern.exchange(
                                                PlanMatchPattern.node(TableScanNode.class))));
                    });

            if (!skipResultsCorrectnessCheckForPushdown) {
                // Compare the results with pushdown disabled, so that explicit matches() call is not needed
                verifyResultsWithPushdownDisabled();
            }
            return this;
        }

        /**
         * Verifies query is not fully pushed down and verifies the results are the same as when the pushdown is fully disabled.
         * <p>
         * <b>Note:</b> the primary intent of this assertion is to ensure the test is updated to {@link #isFullyPushedDown()}
         * when pushdown capabilities are improved.
         */
        @SafeVarargs
        public final QueryAssert isNotFullyPushedDown(Class<? extends PlanNode>... retainedNodes)
        {
            checkArgument(retainedNodes.length > 0, "No retainedNodes");
            PlanMatchPattern expectedPlan = PlanMatchPattern.node(TableScanNode.class);
            for (Class<? extends PlanNode> retainedNode : ImmutableList.copyOf(retainedNodes).reverse()) {
                expectedPlan = PlanMatchPattern.node(retainedNode, expectedPlan);
            }
            return isNotFullyPushedDown(expectedPlan);
        }

        /**
         * Verifies query is not fully pushed down and verifies the results are the same as when the pushdown is fully disabled.
         * <p>
         * <b>Note:</b> the primary intent of this assertion is to ensure the test is updated to {@link #isFullyPushedDown()}
         * when pushdown capabilities are improved.
         */
        public final QueryAssert isNotFullyPushedDown(PlanMatchPattern retainedSubplan)
        {
            PlanMatchPattern expectedPlan = PlanMatchPattern.anyTree(retainedSubplan);

            transaction(runner.getTransactionManager(), runner.getAccessControl())
                    .execute(session, session -> {
                        Plan plan = runner.createPlan(session, query, WarningCollector.NOOP);
                        assertPlan(
                                session,
                                runner.getMetadata(),
                                noopStatsCalculator(),
                                plan,
                                expectedPlan);
                    });

            if (!skipResultsCorrectnessCheckForPushdown) {
                // Compare the results with pushdown disabled, so that explicit matches() call is not needed
                verifyResultsWithPushdownDisabled();
            }
            return this;
        }

        private void verifyResultsWithPushdownDisabled()
        {
            Session withoutPushdown = Session.builder(session)
                    .setSystemProperty("allow_pushdown_into_connectors", "false")
                    .build();
            matches(runner.execute(withoutPushdown, query));
        }
    }

    public static class ExpressionAssert
            extends AbstractAssert<ExpressionAssert, Object>
    {
        private static final StandardRepresentation TYPE_RENDERER = new StandardRepresentation()
        {
            @Override
            public String toStringOf(Object object)
            {
                if (object instanceof SqlTimestamp) {
                    SqlTimestamp timestamp = (SqlTimestamp) object;
                    return String.format(
                            "%s [p = %s, epochMicros = %s, fraction = %s]",
                            timestamp,
                            timestamp.getPrecision(),
                            timestamp.getEpochMicros(),
                            timestamp.getPicosOfMicros());
                }
                else if (object instanceof SqlTimestampWithTimeZone) {
                    SqlTimestampWithTimeZone timestamp = (SqlTimestampWithTimeZone) object;
                    return String.format(
                            "%s [p = %s, epochMillis = %s, fraction = %s, tz = %s]",
                            timestamp,
                            timestamp.getPrecision(),
                            timestamp.getEpochMillis(),
                            timestamp.getPicosOfMilli(),
                            timestamp.getTimeZoneKey());
                }
                else if (object instanceof SqlTime) {
                    SqlTime time = (SqlTime) object;
                    return String.format("%s [picos = %s]", time, time.getPicos());
                }
                else if (object instanceof SqlTimeWithTimeZone) {
                    SqlTimeWithTimeZone time = (SqlTimeWithTimeZone) object;
                    return String.format(
                            "%s [picos = %s, offset = %s]",
                            time,
                            time.getPicos(),
                            time.getOffsetMinutes());
                }

                return Objects.toString(object);
            }
        };

        private final QueryRunner runner;
        private final Session session;
        private final Type actualType;

        static AssertProvider<ExpressionAssert> newExpressionAssert(String expression, QueryRunner runner, Session session)
        {
            MaterializedResult result = runner.execute(session, "VALUES " + expression);
            Type type = result.getTypes().get(0);
            Object value = result.getOnlyColumnAsSet().iterator().next();
            return () -> new ExpressionAssert(runner, session, value, type)
                    .withRepresentation(TYPE_RENDERER);
        }

        public ExpressionAssert(QueryRunner runner, Session session, Object actual, Type actualType)
        {
            super(actual, Object.class);
            this.runner = runner;
            this.session = session;
            this.actualType = actualType;
        }

        public ExpressionAssert isEqualTo(BiFunction<Session, QueryRunner, Object> evaluator)
        {
            return isEqualTo(evaluator.apply(session, runner));
        }

        public ExpressionAssert matches(@Language("SQL") String expression)
        {
            MaterializedResult result = runner.execute(session, "VALUES " + expression);
            Type expectedType = result.getTypes().get(0);
            Object expectedValue = result.getOnlyColumnAsSet().iterator().next();

            return satisfies(actual -> {
                assertThat(actualType).as("Type")
                        .isEqualTo(expectedType);

                assertThat(actual)
                        .withRepresentation(TYPE_RENDERER)
                        .isEqualTo(expectedValue);
            });
        }

        public ExpressionAssert hasType(Type type)
        {
            objects.assertEqual(info, actualType, type);
            return this;
        }
    }
}
