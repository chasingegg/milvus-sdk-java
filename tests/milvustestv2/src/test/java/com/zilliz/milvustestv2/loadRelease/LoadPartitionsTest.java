package com.zilliz.milvustestv2.loadRelease;

import com.google.gson.JsonObject;
import com.google.common.collect.Lists;
import com.zilliz.milvustestv2.common.BaseTest;
import com.zilliz.milvustestv2.common.CommonData;
import com.zilliz.milvustestv2.common.CommonFunction;
import com.zilliz.milvustestv2.utils.GenerateUtil;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @Author yongpeng.li
 * @Date 2024/2/19 14:37
 */
public class LoadPartitionsTest extends BaseTest {
    String newCollection;

    @BeforeClass(alwaysRun = true)
    public void initTestData() {
        newCollection = CommonFunction.createNewCollection(128, null);
        milvusClientV2.createPartition(CreatePartitionReq.builder()
                .collectionName(newCollection)
                .partitionName(CommonData.partitionName)
                .build());
        List<JsonObject> jsonObjects = CommonFunction.generateDefaultData(CommonData.numberEntities, CommonData.dim);
        milvusClientV2.insert(InsertReq.builder().collectionName(newCollection).partitionName(CommonData.partitionName).data(jsonObjects).build());
        CommonFunction.createVectorIndex(newCollection,CommonData.fieldFloatVector, IndexParam.IndexType.HNSW, IndexParam.MetricType.L2);
    }
    @AfterClass(alwaysRun = true)
    public void cleanTestData(){
        milvusClientV2.dropCollection(DropCollectionReq.builder().collectionName(newCollection).build());
    }

    @Test(description = "Load partition",groups = {"Smoke"})
    public void loadPartition(){
        milvusClientV2.loadPartitions(LoadPartitionsReq.builder()
                .collectionName(newCollection)
                .partitionNames(Collections.singletonList(CommonData.partitionName))
                .build());
        SearchResp searchResp = milvusClientV2.search(SearchReq.builder()
                .collectionName(newCollection)
                .outputFields(Lists.newArrayList("*"))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .annsField(CommonData.fieldFloatVector)
                .partitionNames(Lists.newArrayList(CommonData.partitionName))
                .data(GenerateUtil.generateFloatVector(10, 3, CommonData.dim))
                .topK(CommonData.topK)
                .build());
        Assert.assertEquals(searchResp.getSearchResults().size(), CommonData.topK);
    }
}
