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

package org.apache.kylin.rest.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hbase.client.Connection;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.CubeUpdate;
import org.apache.kylin.cube.cuboid.CuboidCLI;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.HBaseColumnDesc;
import org.apache.kylin.cube.model.HBaseColumnFamilyDesc;
import org.apache.kylin.cube.model.HBaseMappingDesc;
import org.apache.kylin.engine.EngineFactory;
import org.apache.kylin.engine.mr.CubingJob;
import org.apache.kylin.engine.mr.HadoopUtil;
import org.apache.kylin.engine.mr.common.HadoopShellExecutable;
import org.apache.kylin.engine.mr.common.MapReduceExecutable;
import org.apache.kylin.job.exception.JobException;
import org.apache.kylin.job.execution.DefaultChainedExecutable;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.metadata.MetadataConstants;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.cachesync.Broadcaster;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.project.ProjectManager;
import org.apache.kylin.metadata.project.RealizationEntry;
import org.apache.kylin.metadata.realization.RealizationStatusEnum;
import org.apache.kylin.metadata.realization.RealizationType;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.exception.InternalErrorException;
import org.apache.kylin.rest.request.MetricsRequest;
import org.apache.kylin.rest.response.HBaseResponse;
import org.apache.kylin.rest.response.MetricsResponse;
import org.apache.kylin.rest.security.AclPermission;
import org.apache.kylin.source.hive.HiveSourceTableLoader;
import org.apache.kylin.source.hive.cardinality.HiveColumnCardinalityJob;
import org.apache.kylin.source.hive.cardinality.HiveColumnCardinalityUpdateJob;
import org.apache.kylin.storage.hbase.HBaseConnection;
import org.apache.kylin.storage.hbase.util.HBaseRegionSizeCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;

/**
 * Stateless & lightweight service facade of cube management functions.
 *
 * @author yangli9
 */
@Component("cubeMgmtService")
public class CubeService extends BasicService implements InitializingBean {
    private static final String DESC_SUFFIX = "_desc";

    private static final Logger logger = LoggerFactory.getLogger(CubeService.class);

    protected Cache<String, HBaseResponse> htableInfoCache = CacheBuilder.newBuilder().build();

    @Autowired
    private AccessService accessService;

    @PostFilter(Constant.ACCESS_POST_FILTER_READ)
    public List<CubeInstance> listAllCubes(final String cubeName, final String projectName, final String modelName) {
        List<CubeInstance> cubeInstances = null;
        ProjectInstance project = (null != projectName) ? getProjectManager().getProject(projectName) : null;

        if (null == project) {
            cubeInstances = getCubeManager().listAllCubes();
        } else {
            cubeInstances = listAllCubes(projectName);
        }

        List<CubeInstance> filterModelCubes = new ArrayList<CubeInstance>();

        if (modelName != null) {
            for (CubeInstance cubeInstance : cubeInstances) {
                boolean isCubeMatch = cubeInstance.getDescriptor().getModelName().toLowerCase().equals(modelName.toLowerCase());
                if (isCubeMatch) {
                    filterModelCubes.add(cubeInstance);
                }
            }
        } else {
            filterModelCubes = cubeInstances;
        }

        List<CubeInstance> filterCubes = new ArrayList<CubeInstance>();
        for (CubeInstance cubeInstance : filterModelCubes) {
            boolean isCubeMatch = (null == cubeName) || cubeInstance.getName().toLowerCase().contains(cubeName.toLowerCase());

            if (isCubeMatch) {
                filterCubes.add(cubeInstance);
            }
        }

        return filterCubes;
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'MANAGEMENT')")
    public CubeInstance updateCubeCost(CubeInstance cube, int cost) throws IOException {

        if (cube.getCost() == cost) {
            // Do nothing
            return cube;
        }
        cube.setCost(cost);

        String owner = SecurityContextHolder.getContext().getAuthentication().getName();
        cube.setOwner(owner);

        CubeUpdate cubeBuilder = new CubeUpdate(cube).setOwner(owner).setCost(cost);

        return getCubeManager().updateCube(cubeBuilder);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or " + Constant.ACCESS_HAS_ROLE_MODELER)
    public CubeInstance createCubeAndDesc(String cubeName, String projectName, CubeDesc desc) throws IOException {
        if (getCubeManager().getCube(cubeName) != null) {
            throw new InternalErrorException("The cube named " + cubeName + " already exists");
        }

        if (getCubeDescManager().getCubeDesc(desc.getName()) != null) {
            throw new InternalErrorException("The cube desc named " + desc.getName() + " already exists");
        }

        String owner = SecurityContextHolder.getContext().getAuthentication().getName();
        CubeDesc createdDesc;
        CubeInstance createdCube;
        
        //将去重指标拆分到多个columnFamily
        splitMeasureColumnFamily(desc);
        
        createdDesc = getCubeDescManager().createCubeDesc(desc);

        if (!createdDesc.getError().isEmpty()) {
            getCubeDescManager().removeCubeDesc(createdDesc);
            throw new InternalErrorException(createdDesc.getError().get(0));
        }

        try {
            int cuboidCount = CuboidCLI.simulateCuboidGeneration(createdDesc, false);
            logger.info("New cube " + cubeName + " has " + cuboidCount + " cuboids");
        } catch (Exception e) {
            getCubeDescManager().removeCubeDesc(createdDesc);
            throw new InternalErrorException("Failed to deal with the request.", e);
        }

        createdCube = getCubeManager().createCube(cubeName, projectName, createdDesc, owner);
        accessService.init(createdCube, AclPermission.ADMINISTRATION);

        ProjectInstance project = getProjectManager().getProject(projectName);
        accessService.inherit(createdCube, project);

        return createdCube;
    }
    
    private void splitMeasureColumnFamily(CubeDesc desc) {
        int columnFamilyMeasureCount = getColumnFamilyMeasureCount(desc);
        if(columnFamilyMeasureCount < 0){
            //默认值-1，不启用，可在配置文件控制
            return;
        }
        if(columnFamilyMeasureCount > desc.getMeasures().size()){
            columnFamilyMeasureCount = desc.getMeasures().size();
        }
        
        HBaseMappingDesc oldMapping = desc.getHbaseMapping();

        List<String> countMeasureList = Lists.newArrayList();
        List<String> countDistinctMeasureList = Lists.newArrayList();
        List<String> otherMeasureList = Lists.newArrayList();
        for(MeasureDesc measure : desc.getMeasures()){
            if(measure.getFunction().isCount()){
                countMeasureList.add(measure.getName());
            }else if(measure.getFunction().isCountDistinct()){
                countDistinctMeasureList.add(measure.getName());
            }else{
                otherMeasureList.add(measure.getName());
            }
        }
        
        int countDistinctMeasureNum = countDistinctMeasureList.size();
        
        if(countDistinctMeasureNum > columnFamilyMeasureCount){
            HBaseMappingDesc newMapping = new HBaseMappingDesc();
            int columnFmailyNum = 1 + (otherMeasureList.size() > 0 ? 1 : 0) + ((countDistinctMeasureNum+columnFamilyMeasureCount-1)/columnFamilyMeasureCount);
            HBaseColumnFamilyDesc[] newColumnFamilyArray = new HBaseColumnFamilyDesc[columnFmailyNum];
            newMapping.setColumnFamily(newColumnFamilyArray);
            newMapping.setCubeRef(oldMapping.getCubeRef());
            
            int cfIndex = 0;
            int fNameIndex = 1;
            //set count measure cf
            HBaseColumnFamilyDesc countHBaseColumnFamily = buildMeasureColumnFamily("F"+fNameIndex++,countMeasureList);
            newColumnFamilyArray[cfIndex++] = countHBaseColumnFamily;
            //set sum measure cf
            if(otherMeasureList.size() > 0){
                HBaseColumnFamilyDesc otherHBaseColumnFamily = buildMeasureColumnFamily("F"+fNameIndex++,countMeasureList);
                newColumnFamilyArray[cfIndex++] = otherHBaseColumnFamily;
            }
            int countDistinctSplitNum = (countDistinctMeasureList.size()+columnFamilyMeasureCount-1) / columnFamilyMeasureCount;
            for(int i = 1;i<=countDistinctSplitNum;i++){
                String fName = "F"+(fNameIndex++);
                int from = (i-1)*columnFamilyMeasureCount;
                int to = i*columnFamilyMeasureCount;
                if(to>=countDistinctMeasureList.size())
                    to = countDistinctMeasureList.size();
                HBaseColumnFamilyDesc cdHBaseColumnFamily = buildMeasureColumnFamily(fName,countDistinctMeasureList.subList(from,to));
                newColumnFamilyArray[cfIndex++] = cdHBaseColumnFamily;
            }
            desc.setHbaseMapping(newMapping);
        }
    }
    
    private Integer getColumnFamilyMeasureCount(CubeDesc desc){
        int measureCount = getConfig().getColumnFamilyMeasureCount();
        String valStr = desc.getOverrideKylinProps().get("kylin.cube.columnfamily.measure.count");
        if(valStr != null){
            try {
                int val = Integer.valueOf(valStr);
                measureCount = val > 0 && val < measureCount ? val : measureCount;
            } catch (Exception e) {
                logger.error("kylin.cube.columnfamily.measure.count parse error",e);
            }
            
        }
        return measureCount;
    }
    
    private HBaseColumnFamilyDesc buildMeasureColumnFamily(String familyName,List<String> measureList){
        HBaseColumnFamilyDesc columnFamily = new HBaseColumnFamilyDesc();
        columnFamily.setName(familyName);
        HBaseColumnDesc[] countHbaseColumnDescs = new HBaseColumnDesc[1];
        HBaseColumnDesc countColumn = new HBaseColumnDesc();
        countColumn.setColumnFamilyName(familyName);
        countColumn.setMeasureRefs(new String[measureList.size()]);
        countColumn.setMeasureIndex(new int[measureList.size()]);
        countColumn.setMeasures(new MeasureDesc[measureList.size()]);
        countColumn.setQualifier("M");
        for(int i = 0;i<measureList.size();i++){
            String measureRef = measureList.get(i);
            countColumn.getMeasureRefs()[i] = measureRef;
        }
        countHbaseColumnDescs[0] = countColumn;
        columnFamily.setColumns(countHbaseColumnDescs);
        return columnFamily;
    }
    
    public List<CubeInstance> listAllCubes(String projectName) {
        ProjectManager projectManager = getProjectManager();
        ProjectInstance project = projectManager.getProject(projectName);
        if (project == null) {
            return Collections.emptyList();
        }
        ArrayList<CubeInstance> result = new ArrayList<CubeInstance>();
        for (RealizationEntry projectDataModel : project.getRealizationEntries()) {
            if (projectDataModel.getType() == RealizationType.CUBE) {
                CubeInstance cube = getCubeManager().getCube(projectDataModel.getRealization());
                if (cube != null)
                    result.add(cube);
                else
                    logger.error("Cube instance " + projectDataModel.getRealization() + " is failed to load");
            }
        }
        return result;
    }

    private boolean isCubeInProject(String projectName, CubeInstance target) {
        ProjectManager projectManager = getProjectManager();
        ProjectInstance project = projectManager.getProject(projectName);
        if (project == null) {
            return false;
        }
        for (RealizationEntry projectDataModel : project.getRealizationEntries()) {
            if (projectDataModel.getType() == RealizationType.CUBE) {
                CubeInstance cube = getCubeManager().getCube(projectDataModel.getRealization());
                if (cube == null) {
                    logger.error("Project " + projectName + " contains realization " + projectDataModel.getRealization() + " which is not found by CubeManager");
                    continue;
                }
                if (cube.equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'MANAGEMENT')")
    public CubeDesc updateCubeAndDesc(CubeInstance cube, CubeDesc desc, String newProjectName, boolean forceUpdate) throws IOException, JobException {

        final List<CubingJob> cubingJobs = listAllCubingJobs(cube.getName(), null, EnumSet.of(ExecutableState.READY, ExecutableState.RUNNING));
        if (!cubingJobs.isEmpty()) {
            throw new JobException("Cube schema shouldn't be changed with running job.");
        }

        try {
            //double check again
            if (!forceUpdate && !cube.getDescriptor().consistentWith(desc)) {
                throw new IllegalStateException("cube's desc is not consistent with the new desc");
            }

            CubeDesc updatedCubeDesc = getCubeDescManager().updateCubeDesc(desc);
            int cuboidCount = CuboidCLI.simulateCuboidGeneration(updatedCubeDesc, false);
            logger.info("Updated cube " + cube.getName() + " has " + cuboidCount + " cuboids");

            ProjectManager projectManager = getProjectManager();
            if (!isCubeInProject(newProjectName, cube)) {
                String owner = SecurityContextHolder.getContext().getAuthentication().getName();
                ProjectInstance newProject = projectManager.moveRealizationToProject(RealizationType.CUBE, cube.getName(), newProjectName, owner);
                accessService.inherit(cube, newProject);
            }

            return updatedCubeDesc;
        } catch (IOException e) {
            throw new InternalErrorException("Failed to deal with the request.", e);
        }
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'MANAGEMENT')")
    public void deleteCube(CubeInstance cube) throws IOException, JobException {
        final List<CubingJob> cubingJobs = listAllCubingJobs(cube.getName(), null, EnumSet.of(ExecutableState.READY, ExecutableState.RUNNING));
        if (!cubingJobs.isEmpty()) {
            throw new JobException("The cube " + cube.getName() + " has running job, please discard it and try again.");
        }

        // 2017-6-6  只有 cube状态为 disabled 并且 segments.size()的大小为0 ，才能删除 cube。 防止误删除 cube 。   cube状态 DESCBROKEN 是指cube desc 描述json信息已损坏，不完整。
        //  用户在页面上删除 cube的时候，先 purge清除数据后，那么 segments.size()为0 ，然后就能删除了。
        if (!((cube.getStatus() == RealizationStatusEnum.DISABLED && cube.getSegments().size() == 0) || cube.getStatus() == RealizationStatusEnum.DESCBROKEN)) {
            throw new InternalErrorException("Only disabled or descbroken cube can be delete, and cube shouldn't has any segments! you can purge first . cube status is  " + cube.getStatus() + ",cube segments size is " + cube.getSegments().size());
        }

        try {
            this.releaseAllJobs(cube);
        } catch (Exception e) {
            logger.error("error when releasing all jobs", e);
            //ignore the exception
        }

        int cubeNum = getCubeManager().getCubesByDesc(cube.getDescriptor().getName()).size();
        getCubeManager().dropCube(cube.getName(), cubeNum == 1);//only delete cube desc when no other cube is using it
        accessService.clean(cube, true);
    }

    public static String getCubeNameFromDesc(String descName) {
        if (descName.toLowerCase().endsWith(DESC_SUFFIX)) {
            return descName.substring(0, descName.toLowerCase().indexOf(DESC_SUFFIX));
        } else {
            return descName;
        }
    }

    /**
     * Stop all jobs belonging to this cube and clean out all segments
     *
     * @param cube
     * @return
     * @throws IOException
     * @throws JobException
     */
    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'OPERATION') or hasPermission(#cube, 'MANAGEMENT')")
    public CubeInstance purgeCube(CubeInstance cube) throws IOException, JobException {

        String cubeName = cube.getName();
        RealizationStatusEnum ostatus = cube.getStatus();
        if (null != ostatus && !RealizationStatusEnum.DISABLED.equals(ostatus)) {
            throw new InternalErrorException("Only disabled cube can be purged, status of " + cubeName + " is " + ostatus);
        }

        try {
            this.releaseAllSegments(cube);
            return cube;
        } catch (IOException e) {
            throw e;
        }

    }

    /**
     * Update a cube status from ready to disabled.
     *
     * @return
     * @throws IOException
     * @throws JobException
     */
    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'OPERATION') or hasPermission(#cube, 'MANAGEMENT')")
    public CubeInstance disableCube(CubeInstance cube) throws IOException, JobException {

        String cubeName = cube.getName();

        RealizationStatusEnum ostatus = cube.getStatus();
        if (null != ostatus && !RealizationStatusEnum.READY.equals(ostatus)) {
            throw new InternalErrorException("Only ready cube can be disabled, status of " + cubeName + " is " + ostatus);
        }

        cube.setStatus(RealizationStatusEnum.DISABLED);

        try {
            CubeUpdate cubeBuilder = new CubeUpdate(cube);
            cubeBuilder.setStatus(RealizationStatusEnum.DISABLED);
            return getCubeManager().updateCube(cubeBuilder);
        } catch (IOException e) {
            cube.setStatus(ostatus);
            throw e;
        }
    }

    /**
     * Update a cube status from disable to ready.
     *
     * @return
     * @throws IOException
     * @throws JobException
     */
    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'OPERATION')  or hasPermission(#cube, 'MANAGEMENT')")
    public CubeInstance enableCube(CubeInstance cube) throws IOException, JobException {
        String cubeName = cube.getName();

        RealizationStatusEnum ostatus = cube.getStatus();
        if (!cube.getStatus().equals(RealizationStatusEnum.DISABLED)) {
            throw new InternalErrorException("Only disabled cube can be enabled, status of " + cubeName + " is " + ostatus);
        }

        if (cube.getSegments(SegmentStatusEnum.READY).size() == 0) {
            throw new InternalErrorException("Cube " + cubeName + " dosen't contain any READY segment");
        }

        final List<CubingJob> cubingJobs = listAllCubingJobs(cube.getName(), null, EnumSet.of(ExecutableState.READY, ExecutableState.RUNNING));
        if (!cubingJobs.isEmpty()) {
            throw new JobException("Enable is not allowed with a running job.");
        }
        if (!cube.getDescriptor().checkSignature()) {
            throw new IllegalStateException("Inconsistent cube desc signature for " + cube.getDescriptor());
        }

        try {
            CubeUpdate cubeBuilder = new CubeUpdate(cube);
            cubeBuilder.setStatus(RealizationStatusEnum.READY);
            return getCubeManager().updateCube(cubeBuilder);
        } catch (IOException e) {
            cube.setStatus(ostatus);
            throw e;
        }
    }

    public MetricsResponse calculateMetrics(MetricsRequest request) {
        List<CubeInstance> cubes = this.getCubeManager().listAllCubes();
        MetricsResponse metrics = new MetricsResponse();
        Date startTime = (null == request.getStartTime()) ? new Date(-1) : request.getStartTime();
        Date endTime = (null == request.getEndTime()) ? new Date() : request.getEndTime();
        metrics.increase("totalCubes", (float) 0);
        metrics.increase("totalStorage", (float) 0);

        for (CubeInstance cube : cubes) {
            Date createdDate = new Date(-1);
            createdDate = (cube.getCreateTimeUTC() == 0) ? createdDate : new Date(cube.getCreateTimeUTC());

            if (createdDate.getTime() > startTime.getTime() && createdDate.getTime() < endTime.getTime()) {
                metrics.increase("totalCubes");
            }
        }

        metrics.increase("aveStorage", (metrics.get("totalCubes") == 0) ? 0 : metrics.get("totalStorage") / metrics.get("totalCubes"));

        return metrics;
    }

    /**
     * Calculate size of each region for given table and other info of the
     * table.
     *
     * @param tableName The table name.
     * @return The HBaseResponse object contains table size, region count. null
     * if error happens.
     * @throws IOException Exception when HTable resource is not closed correctly.
     */
    public HBaseResponse getHTableInfo(String cubeName, String tableName) throws IOException {
        String key = cubeName + "/" + tableName;
        HBaseResponse hr = htableInfoCache.getIfPresent(key);
        if (null != hr) {
            return hr;
        }

        Connection conn = HBaseConnection.get(this.getConfig().getStorageUrl());
        long tableSize = 0;
        int regionCount = 0;

        HBaseRegionSizeCalculator cal = new HBaseRegionSizeCalculator(tableName, conn);
        Map<byte[], Long> sizeMap = cal.getRegionSizeMap();

        for (long s : sizeMap.values()) {
            tableSize += s;
        }

        regionCount = sizeMap.size();

        // Set response.
        hr = new HBaseResponse();
        logger.debug("Loading HTable info " + cubeName + ", " + tableName);
        hr.setTableSize(tableSize);
        hr.setRegionCount(regionCount);
        htableInfoCache.put(key, hr);

        return hr;
    }

    /**
     * Generate cardinality for table This will trigger a hadoop job
     * The result will be merged into table exd info
     *
     * @param tableName
     */
    @PreAuthorize(Constant.ACCESS_HAS_ROLE_MODELER + " or " + Constant.ACCESS_HAS_ROLE_ADMIN)
    public void calculateCardinality(String tableName, String submitter) {
        String[] dbTableName = HadoopUtil.parseHiveTableName(tableName);
        tableName = dbTableName[0] + "." + dbTableName[1];
        TableDesc table = getMetadataManager().getTableDesc(tableName);
        final Map<String, String> tableExd = getMetadataManager().getTableDescExd(tableName);
        if (tableExd == null || table == null) {
            IllegalArgumentException e = new IllegalArgumentException("Cannot find table descirptor " + tableName);
            logger.error("Cannot find table descirptor " + tableName, e);
            throw e;
        }

        DefaultChainedExecutable job = new DefaultChainedExecutable();
        //make sure the job could be scheduled when the DistributedScheduler is enable.
        job.setParam("segmentId", tableName);
        job.setName("Hive Column Cardinality calculation for table '" + tableName + "'");
        job.setSubmitter(submitter);

        String outPath = HiveColumnCardinalityJob.OUTPUT_PATH + "/" + tableName;
        String param = "-table " + tableName + " -output " + outPath;

        MapReduceExecutable step1 = new MapReduceExecutable();

        step1.setMapReduceJobClass(HiveColumnCardinalityJob.class);
        step1.setMapReduceParams(param);
        step1.setParam("segmentId", tableName);

        job.addTask(step1);

        HadoopShellExecutable step2 = new HadoopShellExecutable();

        step2.setJobClass(HiveColumnCardinalityUpdateJob.class);
        step2.setJobParams(param);
        step2.setParam("segmentId", tableName);
        job.addTask(step2);

        getExecutableManager().addJob(job);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'OPERATION')  or hasPermission(#cube, 'MANAGEMENT')")
    public void updateCubeNotifyList(CubeInstance cube, List<String> notifyList) throws IOException {
        CubeDesc desc = cube.getDescriptor();
        desc.setNotifyList(notifyList);
        getCubeDescManager().updateCubeDesc(desc);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'OPERATION')  or hasPermission(#cube, 'MANAGEMENT')")
    public CubeInstance rebuildLookupSnapshot(CubeInstance cube, String segmentName, String lookupTable) throws IOException {
        CubeSegment seg = cube.getSegment(segmentName, SegmentStatusEnum.READY);
        getCubeManager().buildSnapshotTable(seg, lookupTable);

        return cube;
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#cube, 'ADMINISTRATION') or hasPermission(#cube, 'OPERATION')  or hasPermission(#cube, 'MANAGEMENT')")
    public CubeInstance deleteSegment(CubeInstance cube, String segmentName) throws IOException {

        // 2017-6-6  不能删除 中间的segment，推测 kylin的考虑出发点 ：是 因为自动merge功能，如果把中间segment的删了，后续也merge了，可能会造成缺失一些数据。 但把这两行注释掉了，也不会有大的问题，因为 头尾是 相对的，把头删了，那么原先的第二个就会变成 头，也能删。
        //        if (!segmentName.equals(cube.getSegments().get(0).getName()) && !segmentName.equals(cube.getSegments().get(cube.getSegments().size() - 1).getName())) {
        //            throw new IllegalArgumentException("Cannot delete segment '" + segmentName + "' as it is neither the first nor the last segment.");
        //        }
        CubeSegment toDelete = null;

        // segmentname 形如 20170525000000_20170526000000
        for (CubeSegment seg : cube.getSegments()) {
            if (seg.getName().equals(segmentName)) {
                toDelete = seg;
            }
        }

        if (toDelete == null) {
            throw new IllegalArgumentException("Cannot find segment '" + segmentName + "'");
        }

        //        if (toDelete.getStatus() != SegmentStatusEnum.READY) {
        //            throw new IllegalArgumentException("Cannot delete segment '" + segmentName + "' as its status is not READY. Discard the on-going job for it.");
        //        }

        CubeUpdate update = new CubeUpdate(cube);
        update.setToRemoveSegs(new CubeSegment[] { toDelete });
        return CubeManager.getInstance(getConfig()).updateCube(update);
    }

    private void releaseAllJobs(CubeInstance cube) {
        final List<CubingJob> cubingJobs = listAllCubingJobs(cube.getName(), null);
        for (CubingJob cubingJob : cubingJobs) {
            final ExecutableState status = cubingJob.getStatus();
            if (status != ExecutableState.SUCCEED && status != ExecutableState.STOPPED && status != ExecutableState.DISCARDED) {
                getExecutableManager().discardJob(cubingJob.getId());
            }
        }
    }

    /**
     * purge the cube
     *
     * @throws IOException
     * @throws JobException
     */
    private void releaseAllSegments(CubeInstance cube) throws IOException, JobException {
        releaseAllJobs(cube);

        CubeUpdate update = new CubeUpdate(cube);
        update.setToRemoveSegs(cube.getSegments().toArray(new CubeSegment[cube.getSegments().size()]));
        CubeManager.getInstance(getConfig()).updateCube(update);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_MODELER + " or " + Constant.ACCESS_HAS_ROLE_ADMIN)
    public String[] reloadHiveTable(String tables) throws IOException {
        Set<String> loaded = HiveSourceTableLoader.reloadHiveTables(tables.split(","), getConfig());
        return (String[]) loaded.toArray(new String[loaded.size()]);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN)
    public void unLoadHiveTable(String tableName) throws IOException {
        String[] dbTableName = HadoopUtil.parseHiveTableName(tableName);
        tableName = dbTableName[0] + "." + dbTableName[1];
        HiveSourceTableLoader.unLoadHiveTable(tableName.toUpperCase());
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN)
    public void syncTableToProject(String[] tables, String project) throws IOException {
        getProjectManager().addTableDescToProject(tables, project);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN)
    public void removeTableFromProject(String tableName, String projectName) throws IOException {
        String[] dbTableName = HadoopUtil.parseHiveTableName(tableName);
        tableName = dbTableName[0] + "." + dbTableName[1];
        getProjectManager().removeTableDescFromProject(tableName, projectName);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_MODELER + " or " + Constant.ACCESS_HAS_ROLE_ADMIN)
    public void calculateCardinalityIfNotPresent(String[] tables, String submitter) throws IOException {
        MetadataManager metaMgr = getMetadataManager();
        for (String table : tables) {
            Map<String, String> exdMap = metaMgr.getTableDescExd(table);
            if (exdMap == null || !exdMap.containsKey(MetadataConstants.TABLE_EXD_CARDINALITY)) {
                calculateCardinality(table, submitter);
            }
        }
    }

    public void updateOnNewSegmentReady(String cubeName) {
        final KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
        String serverMode = kylinConfig.getServerMode();
        if (Constant.SERVER_MODE_JOB.equals(serverMode.toLowerCase()) || Constant.SERVER_MODE_ALL.equals(serverMode.toLowerCase())) {
            CubeInstance cube = getCubeManager().getCube(cubeName);
            if (cube != null) {
                CubeSegment seg = cube.getLatestBuiltSegment();
                if (seg != null && seg.getStatus() == SegmentStatusEnum.READY) {
                    keepCubeRetention(cubeName);
                    mergeCubeSegment(cubeName);
                }
            }
        }
    }

    private void keepCubeRetention(String cubeName) {
        logger.info("checking keepCubeRetention");
        CubeInstance cube = getCubeManager().getCube(cubeName);
        CubeDesc desc = cube.getDescriptor();
        if (desc.getRetentionRange() <= 0)
            return;

        synchronized (CubeService.class) {
            cube = getCubeManager().getCube(cubeName);
            List<CubeSegment> readySegs = cube.getSegments(SegmentStatusEnum.READY);
            if (readySegs.isEmpty())
                return;

            List<CubeSegment> toRemoveSegs = Lists.newArrayList();
            long tail = readySegs.get(readySegs.size() - 1).getDateRangeEnd();
            long head = tail - desc.getRetentionRange();
            for (CubeSegment seg : readySegs) {
                if (seg.getDateRangeEnd() > 0) { // for streaming cube its initial value is 0
                    if (seg.getDateRangeEnd() <= head) {
                        toRemoveSegs.add(seg);
                    }
                }
            }

            if (toRemoveSegs.size() > 0) {
                CubeUpdate cubeBuilder = new CubeUpdate(cube);
                cubeBuilder.setToRemoveSegs(toRemoveSegs.toArray(new CubeSegment[toRemoveSegs.size()]));
                try {
                    this.getCubeManager().updateCube(cubeBuilder);
                } catch (IOException e) {
                    logger.error("Failed to remove old segment from cube " + cubeName, e);
                }
            }
        }
    }

    private void mergeCubeSegment(String cubeName) {
        CubeInstance cube = getCubeManager().getCube(cubeName);
        if (!cube.needAutoMerge())
            return;

        synchronized (CubeService.class) {
            try {
                cube = getCubeManager().getCube(cubeName);
                Pair<Long, Long> offsets = getCubeManager().autoMergeCubeSegments(cube);
                if (offsets != null) {
                    CubeSegment newSeg = getCubeManager().mergeSegments(cube, 0, 0, offsets.getFirst(), offsets.getSecond(), true);
                    logger.debug("Will submit merge job on " + newSeg);
                    DefaultChainedExecutable job = EngineFactory.createBatchMergeJob(newSeg, "SYSTEM");
                    getExecutableManager().addJob(job);
                } else {
                    logger.debug("Not ready for merge on cube " + cubeName);
                }
            } catch (IOException e) {
                logger.error("Failed to auto merge cube " + cubeName, e);
            }
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Broadcaster.getInstance(getConfig()).registerStaticListener(new HTableInfoSyncListener(), "cube");
    }

    private class HTableInfoSyncListener extends Broadcaster.Listener {
        @Override
        public void onClearAll(Broadcaster broadcaster) throws IOException {
            htableInfoCache.invalidateAll();
        }

        @Override
        public void onEntityChange(Broadcaster broadcaster, String entity, Broadcaster.Event event, String cacheKey)
                throws IOException {
            String cubeName = cacheKey;

            String keyPrefix = cubeName + "/";
            for (String k : htableInfoCache.asMap().keySet()) {
                if (k.startsWith(keyPrefix))
                    htableInfoCache.invalidate(k);
            }

        }
    }

}
