package org.mongodb.hadoop;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClientURI;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.hadoop.examples.treasury.TreasuryYieldXMLConfig;
import com.mongodb.hadoop.util.MongoClientURIBuilder;
import org.junit.Test;

import java.util.List;

import static com.mongodb.hadoop.util.MongoConfigUtil.INPUT_MONGOS_HOSTS;
import static com.mongodb.hadoop.util.MongoConfigUtil.INPUT_QUERY;
import static com.mongodb.hadoop.util.MongoConfigUtil.SPLITS_SLAVE_OK;
import static com.mongodb.hadoop.util.MongoConfigUtil.SPLITS_USE_CHUNKS;
import static com.mongodb.hadoop.util.MongoConfigUtil.SPLITS_USE_RANGEQUERY;
import static com.mongodb.hadoop.util.MongoConfigUtil.SPLITS_USE_SHARDS;
import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestSharded extends BaseShardedTest {

    @Test
    public void testBasicInputSource() {
        new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
            .inputUris(getInputUri())
            .outputUris(getOutputUri())
            .execute(isRunTestInVm());
        compareResults(getMongos().getDB("mongo_hadoop").getCollection("yield_historical.out"), getReference());
    }

    @Test
    public void testMultiMongos() {
        MongoClientURI outputUri = getOutputUri();
        new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
            .param(INPUT_MONGOS_HOSTS, "localhost:27017 localhost:27018")
            .inputUris(getInputUri())
            .outputUris(outputUri)
            .execute(isRunTestInVm());
        compareResults(getMongos().getDB(outputUri.getDatabase()).getCollection(outputUri.getCollection()), getReference());
    }

    @Test
    public void testMultiOutputs() {
        DBObject opCounterBefore1 = (DBObject) getMongos().getDB("admin").command("serverStatus").get("opcounters");
        DBObject opCounterBefore2 = (DBObject) getMongos2().getDB("admin").command("serverStatus").get("opcounters");
        MongoClientURI outputUri = getOutputUri();

        new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
            .inputUris(getInputUri())
            .outputUris(outputUri, new MongoClientURIBuilder(outputUri).port(27018).build())
            .execute(isRunTestInVm());

        compareResults(getMongos().getDB(outputUri.getDatabase()).getCollection(outputUri.getCollection()), getReference());

        DBObject opCounterAfter1 = (DBObject) getMongos().getDB("admin").command("serverStatus").get("opcounters");
        DBObject opCounterAfter2 = (DBObject) getMongos2().getDB("admin").command("serverStatus").get("opcounters");

        compare(opCounterBefore1, opCounterAfter1);
        compare(opCounterBefore2, opCounterAfter2);
    }

    @Test
    public void testRangeQueries() {

        DBCollection collection = getMongos().getDB(getOutputUri().getDatabase()).getCollection(getOutputUri().getCollection());
        collection.drop();

        MapReduceJob job = new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
                               .inputUris(getInputUri())
                               .outputUris(getOutputUri())
                               .param(SPLITS_USE_RANGEQUERY, "true");
        job.execute(isRunTestInVm());

        compareResults(collection, getReference());
        collection.drop();

        job.param(INPUT_QUERY, "{\"_id\":{\"$gt\":{\"$date\":1182470400000}}}").execute(isRunTestInVm());
        // Make sure that this fails when rangequery is used with a query that conflicts
        assertFalse("This collection shouldn't exist because of the failure",
                    getMongos().getDB("mongo_hadoop").getCollectionNames().contains("yield_historical.out"));
    }

    public void testDirectAccess() {
        //        Map<String, String> params = new HashMap<String, String>();
        //        params.put(com.mongodb.hadoop.util.MongoConfigUtil.SPLITS_USE_CHUNKS, "true");
        //        runJob(params, "com.mongodb.hadoop.examples.treasury.TreasuryYieldXMLConfig", null, null);
        //        compareResults(getMongos().getDB("mongo_hadoop").getCollection("yield_historical.out"), getReference());
        //
        DBCollection collection = getMongos().getDB("mongo_hadoop").getCollection("yield_historical.out");
        collection.drop();

        // HADOOP61 - simulate a failed migration by having some docs from one chunk
        // also exist on another shard who does not own that chunk(duplicates)
        DB config = getMongos().getDB("config");

        DBObject chunk = config.getCollection("chunks").findOne(new BasicDBObject("shard", "sh01"));
        DBObject query = new BasicDBObject("_id", new BasicDBObject("$gte", ((DBObject) chunk.get("min")).get("_id"))
                                                      .append("$lt", ((DBObject) chunk.get("max")).get("_id")));
        List<DBObject> data = toList(getMongos().getDB("mongo_hadoop").getCollection("yield_historical.in").find(query));
        DBCollection destination = getShard2().getDB("mongo_hadoop").getCollection("yield_historical.in");
        for (DBObject doc : data) {
            destination.insert(doc, WriteConcern.UNACKNOWLEDGED);
        }

        new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
            .param(SPLITS_SLAVE_OK, "true")
            .param(SPLITS_USE_SHARDS, "true")
            .param(SPLITS_USE_CHUNKS, "false")
            .inputUris(new MongoClientURIBuilder(getInputUri()).readPreference(ReadPreference.secondary()).build())
            .execute(isRunTestInVm());

        compareResults(collection, getReference());
        collection.drop();

        new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
            .inputUris(new MongoClientURIBuilder(getInputUri()).readPreference(ReadPreference.secondary()).build())
            .param(SPLITS_SLAVE_OK, "true")
            .param(SPLITS_USE_SHARDS, "true")
            .param(SPLITS_USE_CHUNKS, "true")
            .execute(isRunTestInVm());
        compareResults(collection, getReference());
    }

    @Test
    public void testShardedClusterWithGtLtQueryFormats() {
        DBCollection collection = getMongos().getDB("mongo_hadoop").getCollection("yield_historical.out");
        collection.drop();


        MapReduceJob job = new MapReduceJob(TreasuryYieldXMLConfig.class.getName())
                               .inputUris(new MongoClientURIBuilder(getInputUri()).readPreference(ReadPreference.secondary()).build())
                               .outputUris(getOutputUri())
                               .param(SPLITS_USE_RANGEQUERY, "true");
        job.execute(isRunTestInVm());

        compareResults(collection, getReference());
        collection.drop();

        job.param(INPUT_QUERY, "{\"_id\":{\"$gt\":{\"$date\":1182470400000}}}")
           .inputUris(getInputUri())
           .execute(isRunTestInVm());
        // Make sure that this fails when rangequery is used with a query that conflicts
        assertEquals(collection.count(), 0);
    }

    private void compare(final DBObject before, final DBObject after) {
        compare("update", before, after);
        compare("command", before, after);
    }

    private void compare(final String field, final DBObject before, final DBObject after) {
        Integer afterValue = (Integer) after.get(field);
        Integer beforeValue = (Integer) before.get(field);
        assertTrue(format("%s should be greater after the job runs.  before:  %d  after:  %d", field, beforeValue, afterValue),
                   afterValue > beforeValue);
    }

}