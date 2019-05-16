/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.query;

import org.dbunit.DatabaseUnitException;
import org.dbunit.assertion.DbUnitAssert;
import org.dbunit.assertion.FailureHandler;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.Columns;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ITableMetaData;
import org.dbunit.dataset.datatype.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * dirty hack to support checking result of SQL with limit
 */
public class HackedDbUnitAssert extends DbUnitAssert {
    private static final Logger logger = LoggerFactory.getLogger(HackedDbUnitAssert.class);

    public void assertEquals(ITable expectedTable, ITable actualTable, FailureHandler failureHandler) throws DatabaseUnitException {
        logger.trace("assertEquals(expectedTable, actualTable, failureHandler) - start");
        logger.debug("assertEquals: expectedTable={}", expectedTable);
        logger.debug("assertEquals: actualTable={}", actualTable);
        logger.debug("assertEquals: failureHandler={}", failureHandler);

        // Do not continue if same instance
        if (expectedTable == actualTable) {
            logger.debug("The given tables reference the same object. Will return immediately. (Table={})", expectedTable);
            return;
        }

        if (failureHandler == null) {
            logger.debug("FailureHandler is null. Using default implementation");
            failureHandler = getDefaultFailureHandler();
        }

        ITableMetaData expectedMetaData = expectedTable.getTableMetaData();
        ITableMetaData actualMetaData = actualTable.getTableMetaData();
        String expectedTableName = expectedMetaData.getTableName();

        //        // Verify row count
        //        int expectedRowsCount = expectedTable.getRowCount();
        //        int actualRowsCount = actualTable.getRowCount();
        //        if (expectedRowsCount != actualRowsCount) {
        //            String msg = "row count (table=" + expectedTableName + ")";
        //            Error error =
        //                    failureHandler.createFailure(msg, String
        //                            .valueOf(expectedRowsCount), String
        //                            .valueOf(actualRowsCount));
        //            logger.error(error.toString());
        //            throw error;
        //        }

        // if both tables are empty, it is not necessary to compare columns, as
        // such
        // comparison
        // can fail if column metadata is different (which could occurs when
        // comparing empty tables)
        if (expectedTable.getRowCount() == 0 &&  actualTable.getRowCount() == 0) {
            logger.debug("Tables are empty, hence equals.");
            return;
        }

        // Put the columns into the same order
        Column[] expectedColumns = Columns.getSortedColumns(expectedMetaData);
        Column[] actualColumns = Columns.getSortedColumns(actualMetaData);

        // Verify columns
        Columns.ColumnDiff columnDiff = Columns.getColumnDiff(expectedMetaData, actualMetaData);
        if (columnDiff.hasDifference()) {
            String message = columnDiff.getMessage();
            Error error = failureHandler.createFailure(message, Columns.getColumnNamesAsString(expectedColumns), Columns.getColumnNamesAsString(actualColumns));
            logger.error(error.toString());
            throw error;
        }

        // Get the datatypes to be used for comparing the sorted columns
        ComparisonColumn[] comparisonCols = getComparisonColumns(expectedTableName, expectedColumns, actualColumns, failureHandler);

        // Finally compare the data
        compareData(expectedTable, actualTable, comparisonCols, failureHandler);
    }

    protected void compareData(ITable expectedTable, ITable actualTable, ComparisonColumn[] comparisonCols, FailureHandler failureHandler) throws DataSetException {
        logger.debug("compareData(expectedTable={}, actualTable={}, " + "comparisonCols={}, failureHandler={}) - start", new Object[] { expectedTable, actualTable, comparisonCols, failureHandler });

        if (expectedTable == null) {
            throw new NullPointerException("The parameter 'expectedTable' must not be null");
        }
        if (actualTable == null) {
            throw new NullPointerException("The parameter 'actualTable' must not be null");
        }
        if (comparisonCols == null) {
            throw new NullPointerException("The parameter 'comparisonCols' must not be null");
        }
        if (failureHandler == null) {
            throw new NullPointerException("The parameter 'failureHandler' must not be null");
        }

        for (int index = 0; index < actualTable.getRowCount(); index++) {
            if (!findRowInExpectedTable(expectedTable, actualTable, comparisonCols, failureHandler, index)) {
                throw new IllegalStateException();
            }
        }

    }

    private boolean findRowInExpectedTable(ITable expectedTable, ITable actualTable, ComparisonColumn[] comparisonCols, FailureHandler failureHandler, int index) throws DataSetException {

        // iterate over all rows
        for (int i = 0; i < expectedTable.getRowCount(); i++) {

            // iterate over all columns of the current row
            for (int j = 0; j < comparisonCols.length; j++) {
                ComparisonColumn compareColumn = comparisonCols[j];

                String columnName = compareColumn.getColumnName();
                DataType dataType = compareColumn.getDataType();

                Object expectedValue = expectedTable.getValue(i, columnName);
                Object actualValue = actualTable.getValue(index, columnName);

                // Compare the values
                if (skipCompare(columnName, expectedValue, actualValue)) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("ignoring comparison " + expectedValue + "=" + actualValue + " on column " + columnName);
                    }
                    continue;
                }

                if (dataType.compare(expectedValue, actualValue) != 0) {
                    break;

                    //                    Difference diff = new Difference(expectedTable, actualTable, i, columnName, expectedValue, actualValue);
                    //
                    //                    // Handle the difference (throw error immediately or something else)
                    //                    failureHandler.handle(diff);
                } else {
                    if (j == comparisonCols.length - 1) {
                        return true;
                    } else {
                        continue;
                    }
                }
            }
        }
        return false;
    }

}
