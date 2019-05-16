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

package org.apache.kylin.storage.gtrecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.KylinConfigExt;
import org.apache.kylin.common.debug.BackdoorToggles;
import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.common.FuzzyValueCombination;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.gridtable.CubeGridTable;
import org.apache.kylin.cube.gridtable.CuboidToGridTableMapping;
import org.apache.kylin.cube.gridtable.RecordComparators;
import org.apache.kylin.cube.gridtable.ScanRangePlannerBase;
import org.apache.kylin.cube.gridtable.SegmentGTStartAndEnd;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.gridtable.GTInfo;
import org.apache.kylin.gridtable.GTRecord;
import org.apache.kylin.gridtable.GTScanRange;
import org.apache.kylin.gridtable.GTScanRequest;
import org.apache.kylin.gridtable.GTScanRequestBuilder;
import org.apache.kylin.gridtable.GTUtil;
import org.apache.kylin.gridtable.IGTComparator;
import org.apache.kylin.metadata.filter.CompareTupleFilter;
import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.filter.TupleFilter.FilterOperatorEnum;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.StorageContext;
import org.apache.kylin.storage.util.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class CubeScanRangePlanner extends ScanRangePlannerBase {

    private static final Logger logger = LoggerFactory.getLogger(CubeScanRangePlanner.class);

    protected int maxScanRanges;
    protected int maxFuzzyKeys;

    //non-GT
    protected CubeSegment cubeSegment;
    protected CubeDesc cubeDesc;
    protected Cuboid cuboid;

    protected StorageContext context;
    private KylinConfigExt extConfig;
    private TblColRef samePartitionCol;
    private Boolean filterSegment;

    private TblColRef dateFilterYearCol;
    private TblColRef dateFilterQuarterCol;
    private TblColRef dateFilterMonthCol;
    private TblColRef dateFilterSundayCol;


    public CubeScanRangePlanner(CubeSegment cubeSegment, Cuboid cuboid, TupleFilter filter, Set<TblColRef> dimensions, Set<TblColRef> groupbyDims, //
            Collection<FunctionDesc> metrics, StorageContext context) {
        this.context = context;

        this.maxScanRanges = KylinConfig.getInstanceFromEnv().getQueryStorageVisitScanRangeMax();
        this.maxFuzzyKeys = KylinConfig.getInstanceFromEnv().getQueryScanFuzzyKeyMax();

        this.cubeSegment = cubeSegment;
        this.cubeDesc = cubeSegment.getCubeDesc();
        this.cuboid = cuboid;
        this.extConfig = (KylinConfigExt) cubeDesc.getConfig();
        this.gtPartitionCol = cubeSegment.getModel().getPartitionDesc().getPartitionDateColumnRef();
        
        Set<TblColRef> filterDims = Sets.newHashSet();
        TupleFilter.collectColumns(filter, filterDims);
        
        CuboidToGridTableMapping mapping = cuboid.getCuboidToGridTableMapping();
        int partitionColumnIndex = -1;
        if (cubeSegment.getModel().getPartitionDesc().isPartitioned()) {
            partitionColumnIndex = mapping.getIndexOf(gtPartitionCol);
            if(partitionColumnIndex == -1){
                this.samePartitionCol = getSamePartitionCol();
                if(samePartitionCol != null){
                    partitionColumnIndex = mapping.getIndexOf(samePartitionCol);
                }
            }
            if(partitionColumnIndex >=0){
                filterSegment = judgeWhetherSkip(filter);
                if(filterSegment){
                    return;
                }
            }else{
                //scan date filter:YEAR,QUARTER,MONTH,SUNDAY
                if(this.extConfig.isScanDateFilterEnable()){
                    //dateLevel: year:1, quarter:2,month:3,sunday:4
                    this.dateFilterYearCol = getDateFilterYearCol();
                    filterSegment = judgeWhetherSkipByDateCol(dateFilterYearCol, 1, mapping, filter);
                    if(filterSegment){
                        return;
                    }

                    this.dateFilterQuarterCol = getDateFilterQuarterCol();
                    filterSegment = judgeWhetherSkipByDateCol(dateFilterQuarterCol, 2, mapping, filter);
                    if(filterSegment){
                        return;
                    }

                    this.dateFilterMonthCol = getDateFilterMonthCol();
                    filterSegment = judgeWhetherSkipByDateCol(dateFilterMonthCol, 3, mapping, filter);
                    if(filterSegment){
                        return;
                    }

                    this.dateFilterSundayCol = getDateFilterSundayCol();
                    filterSegment = judgeWhetherSkipByDateCol(dateFilterSundayCol, 4, mapping, filter);
                    if(filterSegment){
                        return;
                    }
                }
            }
        }
        
        this.filterSegment = false;
        this.gtInfo = CubeGridTable.newGTInfo(cubeSegment, cuboid.getId());

        IGTComparator comp = gtInfo.getCodeSystem().getComparator();
        //start key GTRecord compare to start key GTRecord
        this.rangeStartComparator = RecordComparators.getRangeStartComparator(comp);
        //stop key GTRecord compare to stop key GTRecord
        this.rangeEndComparator = RecordComparators.getRangeEndComparator(comp);
        //start key GTRecord compare to stop key GTRecord
        this.rangeStartEndComparator = RecordComparators.getRangeStartEndComparator(comp);

        //replace the constant values in filter to dictionary codes 
        this.gtFilter = GTUtil.convertFilterColumnsAndConstants(filter, gtInfo, mapping.getCuboidDimensionsInGTOrder(), groupbyDims);

        this.gtDimensions = mapping.makeGridTableColumns(dimensions);
        this.gtAggrGroups = mapping.makeGridTableColumns(replaceDerivedColumns(groupbyDims, cubeSegment.getCubeDesc()));
        this.gtAggrMetrics = mapping.makeGridTableColumns(metrics);
        this.gtAggrFuncs = mapping.makeAggrFuncs(metrics);

        if (partitionColumnIndex >= 0) {
            SegmentGTStartAndEnd segmentGTStartAndEnd = new SegmentGTStartAndEnd(cubeSegment, gtInfo);
            this.gtStartAndEnd = segmentGTStartAndEnd.getSegmentStartAndEnd(partitionColumnIndex);
            this.isPartitionColUsingDatetimeEncoding = segmentGTStartAndEnd.isUsingDatetimeEncoding(partitionColumnIndex);
            this.gtPartitionCol = gtInfo.colRef(partitionColumnIndex);
        }

    }

    private Boolean judgeWhetherSkipByDateCol(TblColRef dateFilterColRef, int dateLevel, CuboidToGridTableMapping cuboidToGridTableMapping, TupleFilter filter){
        //check
        if(dateFilterColRef == null){
            return false;
        }
        int dateColumnIndex = -1;
        dateColumnIndex = cuboidToGridTableMapping.getIndexOf(dateFilterColRef);
        if(dateColumnIndex < 0){
            return false;
        }

        //judge whether skip
        int start = getStartIntValue(cubeSegment.getName());
        int end = getEndIntValue(cubeSegment.getName());
        for(TupleFilter child : filter.getChildren()){
            if(child instanceof CompareTupleFilter){
                CompareTupleFilter compareFilter = (CompareTupleFilter) child;
                if(dateFilterColRef.equals(compareFilter.getColumn())){
                    if(filterSeg(compareFilter,dateLevel,start,end)){
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Boolean judgeWhetherSkip(TupleFilter filter){
        if(filter == null)
            return false;
        int start = getStartIntValue(cubeSegment.getName());
        int end = getEndIntValue(cubeSegment.getName());
        for(TupleFilter child : filter.getChildren()){
            if(child instanceof CompareTupleFilter){
                CompareTupleFilter compareFilter = (CompareTupleFilter) child;
                if(gtPartitionCol.equals(compareFilter.getColumn()) || (samePartitionCol != null && samePartitionCol.equals(compareFilter.getColumn()))){
                    if(filterSeg(compareFilter,start,end)){
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private TblColRef getSamePartitionCol(){
        String samePartitionColConf = extConfig.getKylinModelPartitionSameCol();
        if(samePartitionColConf != null && !"".equals(samePartitionColConf)){
            String[] colInfo = samePartitionColConf.split("\\.");
            if(colInfo.length == 3){
                String table = colInfo[0]+"."+colInfo[1];
                TableRef tblRef = this.cubeSegment.getModel().findTable(table.toUpperCase());
                return tblRef.getColumn(colInfo[2].toUpperCase());
            }
        }
        return null;
    }

    private TblColRef getDateFilterYearCol(){
        String dateFilterYearColConf = extConfig.getDateFilterYearCol();
        if(dateFilterYearColConf != null && !"".equals(dateFilterYearColConf)){
            String[] colInfo = dateFilterYearColConf.split("\\.");
            if(colInfo.length == 3){
                String table = colInfo[0]+"."+colInfo[1];
                TableRef tblRef = this.cubeSegment.getModel().findTable(table.toUpperCase());
                return tblRef.getColumn(colInfo[2].toUpperCase());
            }
        }
        return null;
    }

    private TblColRef getDateFilterQuarterCol(){
        String dateFilterQuarterColConf = extConfig.getDateFilterQuarterCol();
        if(dateFilterQuarterColConf != null && !"".equals(dateFilterQuarterColConf)){
            String[] colInfo = dateFilterQuarterColConf.split("\\.");
            if(colInfo.length == 3){
                String table = colInfo[0]+"."+colInfo[1];
                TableRef tblRef = this.cubeSegment.getModel().findTable(table.toUpperCase());
                return tblRef.getColumn(colInfo[2].toUpperCase());
            }
        }
        return null;
    }

    private TblColRef getDateFilterMonthCol(){
        String dateFilterMonthColConf = extConfig.getDateFilterMonthCol();
        if(dateFilterMonthColConf != null && !"".equals(dateFilterMonthColConf)){
            String[] colInfo = dateFilterMonthColConf.split("\\.");
            if(colInfo.length == 3){
                String table = colInfo[0]+"."+colInfo[1];
                TableRef tblRef = this.cubeSegment.getModel().findTable(table.toUpperCase());
                return tblRef.getColumn(colInfo[2].toUpperCase());
            }
        }
        return null;
    }

    private TblColRef getDateFilterSundayCol(){
        String dateFilterSundayColConf = extConfig.getDateFilterSundayCol();
        if(dateFilterSundayColConf != null && !"".equals(dateFilterSundayColConf)){
            String[] colInfo = dateFilterSundayColConf.split("\\.");
            if(colInfo.length == 3){
                String table = colInfo[0]+"."+colInfo[1];
                TableRef tblRef = this.cubeSegment.getModel().findTable(table.toUpperCase());
                return tblRef.getColumn(colInfo[2].toUpperCase());
            }
        }
        return null;
    }
    
    private boolean filterSeg(CompareTupleFilter compareFilter, int start, int end) {
        if(compareFilter.getOperator() == FilterOperatorEnum.EQ){
            Object value = compareFilter.getFirstValue();
            Integer intValue = 0;
            if(value instanceof String){
                intValue = Integer.parseInt(value.toString());
            }else if(value instanceof Integer){
                intValue = (Integer) value;
            }
            if(intValue >=start && intValue < end){
                return false;
            }
        }else if(compareFilter.getOperator() == FilterOperatorEnum.IN){
            for(Object value : compareFilter.getValues()){
                Integer intValue = 0;
                if(value instanceof String){
                    intValue = Integer.parseInt(value.toString());
                }else if(value instanceof Integer){
                    intValue = (Integer) value;
                }
                if(intValue >=start && intValue < end){
                    return false;
                }
            }
        }else if(compareFilter.getOperator() == FilterOperatorEnum.GT){
            Object value = compareFilter.getFirstValue();
            Integer intValue = 0;
            if(value instanceof String){
                intValue = Integer.parseInt(value.toString());
            }else if(value instanceof Integer){
                intValue = (Integer) value;
            }
            if(start > intValue || intValue < end){
                return false;
            }
        }else if(compareFilter.getOperator() == FilterOperatorEnum.GTE){
            Object value = compareFilter.getFirstValue();
            Integer intValue = 0;
            if(value instanceof String){
                intValue = Integer.parseInt(value.toString());
            }else if(value instanceof Integer){
                intValue = (Integer) value;
            }
            if(start >= intValue || intValue < end){
                return false;
            }
        }else if(compareFilter.getOperator() == FilterOperatorEnum.LT){
            Object value = compareFilter.getFirstValue();
            Integer intValue = 0;
            if(value instanceof String){
                intValue = Integer.parseInt(value.toString());
            }else if(value instanceof Integer){
                intValue = (Integer) value;
            }
            if(intValue >start || end < intValue){
                return false;
            }
        }else if(compareFilter.getOperator() == FilterOperatorEnum.LTE){
            Object value = compareFilter.getFirstValue();
            Integer intValue = 0;
            if(value instanceof String){
                intValue = Integer.parseInt(value.toString());
            }else if(value instanceof Integer){
                intValue = (Integer) value;
            }
            if(intValue >=start || end < intValue){
                return false;
            }
        }
        return true;
    }

    private boolean filterSeg(CompareTupleFilter compareFilter,int dateLevel, int start, int end) {
        try {
            if (compareFilter.getOperator() == FilterOperatorEnum.EQ) {
                Object value = compareFilter.getFirstValue();
                String strValue = null;
                if (value instanceof Integer) {
                    strValue = String.valueOf(value);
                } else if (value instanceof String) {
                    strValue = value.toString();
                }
                Integer startTime = getStartTime(strValue, dateLevel);
                Integer endTime = getEndTime(strValue, dateLevel);
                if (startTime < end && endTime >= start) {
                    return false;
                }
            } else if (compareFilter.getOperator() == FilterOperatorEnum.IN) {
                for (Object value : compareFilter.getValues()) {
                    String strValue = null;
                    if (value instanceof Integer) {
                        strValue = String.valueOf(value);
                    } else if (value instanceof String) {
                        strValue = value.toString();
                    }
                    Integer startTime = getStartTime(strValue, dateLevel);
                    Integer endTime = getEndTime(strValue, dateLevel);
                    if (startTime < end && endTime >= start) {
                        return false;
                    }
                }
            } else if (compareFilter.getOperator() == FilterOperatorEnum.GT) {
                Object value = compareFilter.getFirstValue();
                String strValue = null;
                if (value instanceof Integer) {
                    strValue = String.valueOf(value);
                } else if (value instanceof String) {
                    strValue = value.toString();
                }
                Integer endTime = getEndTime(strValue, dateLevel);
                if (endTime < end) {
                    return false;
                }
            } else if (compareFilter.getOperator() == FilterOperatorEnum.GTE) {
                Object value = compareFilter.getFirstValue();
                String strValue = null;
                if (value instanceof Integer) {
                    strValue = String.valueOf(value);
                } else if (value instanceof String) {
                    strValue = value.toString();
                }
                Integer startTime = getStartTime(strValue, dateLevel);
                if (startTime < end) {
                    return false;
                }
            } else if (compareFilter.getOperator() == FilterOperatorEnum.LT) {
                Object value = compareFilter.getFirstValue();
                String strValue = null;
                if (value instanceof Integer) {
                    strValue = String.valueOf(value);
                } else if (value instanceof String) {
                    strValue = value.toString();
                }
                Integer startTime = getStartTime(strValue, dateLevel);
                if (startTime > start ) {
                    return false;
                }
            } else if (compareFilter.getOperator() == FilterOperatorEnum.LTE) {
                Object value = compareFilter.getFirstValue();
                String strValue = null;
                if (value instanceof Integer) {
                    strValue = String.valueOf(value);
                } else if (value instanceof String) {
                    strValue = value.toString();
                }
                Integer endTime = getEndTime(strValue, dateLevel);
                if (endTime >= start ) {
                    return false;
                }
            }
            return true;
        }catch (Exception e){
            logger.error(e.getMessage());
            return false;
        }
    }

    private Integer getStartTime(String date, int dateLevel)throws Exception{
        if(date == null){
            throw new Exception("date is null");
        }
        int year,quarter,month,day;
        switch (dateLevel){
            case 1:
                year = Integer.parseInt(date);
                return DateUtil.getFirstDayOfYear(year);
            case 2:
                year = Integer.parseInt(date.substring(0, 4));
                quarter = Integer.parseInt(date.substring(4, 5));
                return DateUtil.getFirstDayOfQuarter(year, quarter);
            case 3:
                year = Integer.parseInt(date.substring(0, 4));
                month = Integer.parseInt(date.substring(4, 6));
                return DateUtil.getFirstDayOfMonth(year, month);
            case 4:
                year = Integer.parseInt(date.substring(0, 4));
                month = Integer.parseInt(date.substring(4, 6));
                day = Integer.parseInt(date.substring(6, 8));
                return DateUtil.getFirstDayOfWeek(year, month, day);
            default:
                throw new Exception("dateLevel is error, it should in (1, 2, 3, 4) ");
        }
    }

    private Integer getEndTime(String date, int dateLevel)throws Exception{
        if(date == null){
            throw new Exception("date is null");
        }
        int year,quarter,month,day;
        switch (dateLevel){
            case 1:
                year = Integer.parseInt(date);
                return DateUtil.getLastDayOfYear(year);
            case 2:
                year = Integer.parseInt(date.substring(0, 4));
                quarter = Integer.parseInt(date.substring(4, 5));
                return DateUtil.getLastDayOfQuarter(year, quarter);
            case 3:
                year = Integer.parseInt(date.substring(0, 4));
                month = Integer.parseInt(date.substring(4, 6));
                return DateUtil.getLastDayOfMonth(year, month);
            case 4:
                year = Integer.parseInt(date.substring(0, 4));
                month = Integer.parseInt(date.substring(4, 6));
                day = Integer.parseInt(date.substring(6, 8));
                return DateUtil.getLastDayOfWeek(year, month, day);
            default:
                throw new Exception("dateLevel is error, it should in (1, 2, 3, 4) ");
        }
    }

    private Integer getStartIntValue(String segName){
        String start = segName.split("_")[0];
        return Integer.parseInt(start.substring(0, 8));
    }
    
    private Integer getEndIntValue(String segName){
        String start = segName.split("_")[1];
        return Integer.parseInt(start.substring(0, 8));
    }
    
    /**
     * constrcut GTScanRangePlanner with incomplete information. only be used for UT  
     * @param info
     * @param gtStartAndEnd
     * @param gtPartitionCol
     * @param gtFilter
     */
    public CubeScanRangePlanner(GTInfo info, Pair<ByteArray, ByteArray> gtStartAndEnd, TblColRef gtPartitionCol, TupleFilter gtFilter) {

        this.maxScanRanges = KylinConfig.getInstanceFromEnv().getQueryStorageVisitScanRangeMax();
        this.maxFuzzyKeys = KylinConfig.getInstanceFromEnv().getQueryScanFuzzyKeyMax();

        this.gtInfo = info;

        IGTComparator comp = gtInfo.getCodeSystem().getComparator();
        //start key GTRecord compare to start key GTRecord
        this.rangeStartComparator = RecordComparators.getRangeStartComparator(comp);
        //stop key GTRecord compare to stop key GTRecord
        this.rangeEndComparator = RecordComparators.getRangeEndComparator(comp);
        //start key GTRecord compare to stop key GTRecord
        this.rangeStartEndComparator = RecordComparators.getRangeStartEndComparator(comp);

        this.gtFilter = gtFilter;
        this.gtStartAndEnd = gtStartAndEnd;
        this.gtPartitionCol = gtPartitionCol;
    }

    public GTScanRequest planScanRequest() {
        GTScanRequest scanRequest;
        List<GTScanRange> scanRanges = this.planScanRanges();
        if (scanRanges != null && scanRanges.size() != 0) {
            GTScanRequestBuilder builder = new GTScanRequestBuilder().setInfo(gtInfo).setRanges(scanRanges).setDimensions(gtDimensions).//
                    setAggrGroupBy(gtAggrGroups).setAggrMetrics(gtAggrMetrics).setAggrMetricsFuncs(gtAggrFuncs).setFilterPushDown(gtFilter).//
                    setAllowStorageAggregation(context.isNeedStorageAggregation()).setAggCacheMemThreshold(cubeSegment.getCubeInstance().getConfig().getQueryCoprocessorMemGB()).//
                    setStorageScanRowNumThreshold(context.getThreshold());

            if (context.getFinalPushDownLimit() != Integer.MAX_VALUE)
                builder.setStoragePushDownLimit(context.getFinalPushDownLimit());

            scanRequest = builder.createGTScanRequest();
        } else {
            scanRequest = null;
        }
        return scanRequest;
    }

    /**
     * Overwrite this method to provide smarter storage visit plans
     * @return
     */
    public List<GTScanRange> planScanRanges() {
        TupleFilter flatFilter = flattenToOrAndFilter(gtFilter);

        List<Collection<ColumnRange>> orAndDimRanges = translateToOrAndDimRanges(flatFilter);

        List<GTScanRange> scanRanges = Lists.newArrayListWithCapacity(orAndDimRanges.size());
        for (Collection<ColumnRange> andDimRanges : orAndDimRanges) {
            GTScanRange scanRange = newScanRange(andDimRanges);
            if (scanRange != null)
                scanRanges.add(scanRange);
        }

        List<GTScanRange> mergedRanges = mergeOverlapRanges(scanRanges);
        mergedRanges = mergeTooManyRanges(mergedRanges, maxScanRanges);

        return mergedRanges;
    }

    private Set<TblColRef> replaceDerivedColumns(Set<TblColRef> input, CubeDesc cubeDesc) {
        Set<TblColRef> ret = Sets.newHashSet();
        for (TblColRef col : input) {
            if (cubeDesc.hasHostColumn(col)) {
                for (TblColRef host : cubeDesc.getHostInfo(col).columns) {
                    ret.add(host);
                }
            } else {
                ret.add(col);
            }
        }
        return ret;
    }

    protected GTScanRange newScanRange(Collection<ColumnRange> andDimRanges) {
        GTRecord pkStart = new GTRecord(gtInfo);
        GTRecord pkEnd = new GTRecord(gtInfo);
        Map<Integer, Set<ByteArray>> fuzzyValues = Maps.newHashMap();

        List<GTRecord> fuzzyKeys;
        
        for (ColumnRange range : andDimRanges) {
            if (gtPartitionCol != null && (range.column.equals(gtPartitionCol) || range.column.equals(samePartitionCol))) {
                int beginCompare = rangeStartEndComparator.comparator.compare(range.begin, gtStartAndEnd.getSecond());
                int endCompare = rangeStartEndComparator.comparator.compare(gtStartAndEnd.getFirst(), range.end);

                if ((isPartitionColUsingDatetimeEncoding && endCompare <= 0 && beginCompare < 0) || (!isPartitionColUsingDatetimeEncoding && endCompare <= 0 && beginCompare <= 0)) {
                    //segment range is [Closed,Open), but segmentStartAndEnd.getSecond() might be rounded when using dict encoding, so use <= when has equals in condition. 
                } else {
//                    logger.debug("Pre-check partition col filter failed, partitionColRef {}, segment start {}, segment end {}, range begin {}, range end {}", //
//                            gtPartitionCol, makeReadable(gtStartAndEnd.getFirst()), makeReadable(gtStartAndEnd.getSecond()), makeReadable(range.begin), makeReadable(range.end));
                    return null;
                }
            }

            int col = range.column.getColumnDesc().getZeroBasedIndex();
            if (!gtInfo.getPrimaryKey().get(col))
                continue;

            pkStart.set(col, range.begin);
            pkEnd.set(col, range.end);

            if (range.valueSet != null && !range.valueSet.isEmpty()) {
                fuzzyValues.put(col, range.valueSet);
            }
        }

        fuzzyKeys =

                buildFuzzyKeys(fuzzyValues);
        return new GTScanRange(pkStart, pkEnd, fuzzyKeys);
    }

    private List<GTRecord> buildFuzzyKeys(Map<Integer, Set<ByteArray>> fuzzyValueSet) {
        ArrayList<GTRecord> result = Lists.newArrayList();

        if (fuzzyValueSet.isEmpty())
            return result;

        // debug/profiling purpose
        if (BackdoorToggles.getDisableFuzzyKey()) {
            logger.info("The execution of this query will not use fuzzy key");
            return result;
        }

        List<Map<Integer, ByteArray>> fuzzyValueCombinations = FuzzyValueCombination.calculate(fuzzyValueSet, maxFuzzyKeys);

        for (Map<Integer, ByteArray> fuzzyValue : fuzzyValueCombinations) {

            //            BitSet bitSet = new BitSet(gtInfo.getColumnCount());
            //            for (Map.Entry<Integer, ByteArray> entry : fuzzyValue.entrySet()) {
            //                bitSet.set(entry.getKey());
            //            }
            GTRecord fuzzy = new GTRecord(gtInfo);
            for (Map.Entry<Integer, ByteArray> entry : fuzzyValue.entrySet()) {
                fuzzy.set(entry.getKey(), entry.getValue());
            }

            result.add(fuzzy);
        }
        return result;
    }

    protected List<GTScanRange> mergeOverlapRanges(List<GTScanRange> ranges) {
        if (ranges.size() <= 1) {
            return ranges;
        }

        // sort ranges by start key
        Collections.sort(ranges, new Comparator<GTScanRange>() {
            @Override
            public int compare(GTScanRange a, GTScanRange b) {
                return rangeStartComparator.compare(a.pkStart, b.pkStart);
            }
        });

        // merge the overlap range
        List<GTScanRange> mergedRanges = new ArrayList<GTScanRange>();
        int mergeBeginIndex = 0;
        GTRecord mergeEnd = ranges.get(0).pkEnd;
        for (int index = 1; index < ranges.size(); index++) {
            GTScanRange range = ranges.get(index);

            // if overlap, swallow it
            if (rangeStartEndComparator.compare(range.pkStart, mergeEnd) <= 0) {
                mergeEnd = rangeEndComparator.max(mergeEnd, range.pkEnd);
                continue;
            }

            // not overlap, split here
            GTScanRange mergedRange = mergeKeyRange(ranges.subList(mergeBeginIndex, index));
            mergedRanges.add(mergedRange);

            // start new split
            mergeBeginIndex = index;
            mergeEnd = range.pkEnd;
        }

        // don't miss the last range
        GTScanRange mergedRange = mergeKeyRange(ranges.subList(mergeBeginIndex, ranges.size()));
        mergedRanges.add(mergedRange);

        return mergedRanges;
    }

    private GTScanRange mergeKeyRange(List<GTScanRange> ranges) {
        GTScanRange first = ranges.get(0);
        if (ranges.size() == 1)
            return first;

        GTRecord start = first.pkStart;
        GTRecord end = first.pkEnd;
        List<GTRecord> newFuzzyKeys = new ArrayList<GTRecord>();

        boolean hasNonFuzzyRange = false;
        for (GTScanRange range : ranges) {
            hasNonFuzzyRange = hasNonFuzzyRange || range.fuzzyKeys.isEmpty();
            newFuzzyKeys.addAll(range.fuzzyKeys);
            end = rangeEndComparator.max(end, range.pkEnd);
        }

        // if any range is non-fuzzy, then all fuzzy keys must be cleared
        // also too many fuzzy keys will slow down HBase scan
        if (hasNonFuzzyRange || newFuzzyKeys.size() > maxFuzzyKeys) {
            newFuzzyKeys.clear();
        }

        return new GTScanRange(start, end, newFuzzyKeys);
    }

    protected List<GTScanRange> mergeTooManyRanges(List<GTScanRange> ranges, int maxRanges) {
        if (ranges.size() <= maxRanges) {
            return ranges;
        }

        // TODO: check the distance between range and merge the large distance range
        List<GTScanRange> result = new ArrayList<GTScanRange>(1);
        GTScanRange mergedRange = mergeKeyRange(ranges);
        result.add(mergedRange);
        return result;
    }

    public int getMaxScanRanges() {
        return maxScanRanges;
    }

    public void setMaxScanRanges(int maxScanRanges) {
        this.maxScanRanges = maxScanRanges;
    }
    
    public boolean isFilterSegment(){
        return filterSegment;
    }

}
