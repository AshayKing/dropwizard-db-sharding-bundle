/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.dao.interceptors.DaoClassLocalObserver;
import io.appform.dropwizard.sharding.dao.interceptors.EntityClassThreadLocalObserver;
import io.appform.dropwizard.sharding.dao.interceptors.InterceptorTestUtil;
import io.appform.dropwizard.sharding.dao.testdata.entities.RelationalEntity;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.val;
import org.apache.commons.lang3.RandomUtils;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RelationalDaoTest {

    private final List<SessionFactory> sessionFactories = Lists.newArrayList();
    private RelationalDao<RelationalEntity> relationalDao;

    private ShardManager shardManager;
    private ShardCalculator<String> shardCalculator;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(RelationalEntity.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                        configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @BeforeEach
    public void before() {
        for (int i = 0; i < 16; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        this.shardManager = new BalancedShardManager(sessionFactories.size());
        this.shardCalculator = new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));
        final ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        relationalDao = new RelationalDao<>(sessionFactories,
                RelationalEntity.class,
                this.shardCalculator,
                shardInfoProvider,
                new EntityClassThreadLocalObserver(
                        new DaoClassLocalObserver(
                                new TerminalTransactionObserver())));
    }

    @AfterEach
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    public void testBulkSave() throws Exception {
        String key = "testPhone";
        RelationalEntity entityOne = RelationalEntity.builder()
                .key("1")
                .value("abcd")
                .build();
        RelationalEntity entityTwo = RelationalEntity.builder()
                .key("2")
                .value("abcd")
                .build();
        relationalDao.saveAll(key, Lists.newArrayList(entityOne, entityTwo));
        List<RelationalEntity> entities = relationalDao.select(key,
                DetachedCriteria.forClass(RelationalEntity.class),
                0,
                10);
        assertEquals(2, entities.size());

    }

    @Test
    public void testUpdateUsingQuery() throws Exception {
        val relationalKey = UUID.randomUUID().toString();

        val entityOne = RelationalEntity.builder()
                .key("1")
                .keyTwo("1")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityOne);

        val entityTwo = RelationalEntity.builder()
                .key("2")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityTwo);

        val entityThree = RelationalEntity.builder()
                .key("3")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityThree);


        val newValue = UUID.randomUUID().toString();
        int rowsUpdated = relationalDao.updateUsingQuery(relationalKey,
                UpdateOperationMeta.builder()
                        .queryName("testUpdateUsingKeyTwo")
                        .params(ImmutableMap.of("keyTwo", "2",
                                "value", newValue))
                        .build()
        );
        assertEquals(2, rowsUpdated);

        val persistedEntityTwo = relationalDao.get(relationalKey, "2").orElse(null);
        assertNotNull(persistedEntityTwo);
        assertEquals(newValue, persistedEntityTwo.getValue());

        val persistedEntityThree = relationalDao.get(relationalKey, "3").orElse(null);
        assertNotNull(persistedEntityThree);
        assertEquals(newValue, persistedEntityThree.getValue());


    }

    @Test
    public void testUpdateUsingQueryNoRowUpdated() throws Exception {
        val relationalKey = UUID.randomUUID().toString();

        val entityOne = RelationalEntity.builder()
                .key("1")
                .keyTwo("1")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityOne);

        val entityTwo = RelationalEntity.builder()
                .key("2")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityTwo);

        val entityThree = RelationalEntity.builder()
                .key("3")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityThree);


        val newValue = UUID.randomUUID().toString();
        int rowsUpdated = relationalDao.updateUsingQuery(relationalKey,
                UpdateOperationMeta.builder()
                        .queryName("testUpdateUsingKeyTwo")
                        .params(ImmutableMap.of("keyTwo",
                                UUID.randomUUID().toString(),
                                "value",
                                newValue))
                        .build()
        );
        assertEquals(0, rowsUpdated);


        val persistedEntityOne = relationalDao.get(relationalKey, "1").orElse(null);
        assertNotNull(persistedEntityOne);
        assertEquals(entityOne.getValue(), persistedEntityOne.getValue());

        val persistedEntityTwo = relationalDao.get(relationalKey, "2").orElse(null);
        assertNotNull(persistedEntityTwo);
        assertEquals(entityTwo.getValue(), persistedEntityTwo.getValue());

        val persistedEntityThree = relationalDao.get(relationalKey, "3").orElse(null);
        assertNotNull(persistedEntityThree);
        assertEquals(entityThree.getValue(), persistedEntityThree.getValue());
    }

    @Test
    public void testSaveWithInterceptors() throws Exception {
        val relationalKey = UUID.randomUUID().toString();

        val entityOne = RelationalEntity.builder()
                .key("1")
                .keyTwo("1")
                .value(UUID.randomUUID().toString())
                .build();
        MDC.clear();
        relationalDao.save(relationalKey, entityOne);
        InterceptorTestUtil.validateThreadLocal(RelationalDao.class, RelationalEntity.class);
    }

    @Test
    public void testPersistenceAndQueryOnSameShard() throws Exception {
        val numOfRecords = 100;
        val relationalKeys = generateIdsInSameShard(numOfRecords);
        for (String relationalKey : relationalKeys) {
            relationalDao.save(relationalKey, RelationalEntity.builder()
                    .key(relationalKey)
                    .keyTwo(relationalKey)
                    .value(UUID.randomUUID().toString())
                    .build());
        }

        /* Test that the same shard has all the records by selecting any key at random and using it to fetch all
         records */

        val randomRelationalKey = relationalKeys.get(RandomUtils.nextInt(0, relationalKeys.size()));
        long count = relationalDao.count(randomRelationalKey, (queryRoot, query, criteriaBuilder) -> {
        });
        Assertions.assertEquals(numOfRecords, count);

        /*
        Basic filter test to check equality constraint through QuerySpec
         */
        List<RelationalEntity> queryResultOne = relationalDao.select(randomRelationalKey,
                (queryRoot, query, criteriaBuilder)
                        -> query.where(criteriaBuilder.equal(queryRoot.get("keyTwo"), randomRelationalKey)), 0, numOfRecords);
        Assertions.assertEquals(1, queryResultOne.size());

        /*
        Basic filter test to check in-equality constraint through QuerySpec
         */
        List<RelationalEntity> queryResultTwo = relationalDao.select(randomRelationalKey,
                (queryRoot, query, criteriaBuilder)
                        -> query.where(criteriaBuilder.notEqual(queryRoot.get("keyTwo"), randomRelationalKey)), 0, numOfRecords);
        Assertions.assertEquals(numOfRecords - 1, queryResultTwo.size());

        /*
        Basic filter test to check multiple predicates through QuerySpec
         */
        List<RelationalEntity> queryResultThree = relationalDao.select(randomRelationalKey,
                (queryRoot, query, criteriaBuilder) ->
                        query.where(
                                criteriaBuilder.and(
                                        criteriaBuilder.equal(queryRoot.get("key"), randomRelationalKey),
                                        criteriaBuilder.notEqual(queryRoot.get("keyTwo"), randomRelationalKey)
                                )
                        ), 0, numOfRecords);
        Assertions.assertEquals(0, queryResultThree.size());
    }


    private List<String> generateIdsInSameShard(final int numIdsToBeGenerated) {
        int expectedShardIndex = RandomUtils.nextInt(0, sessionFactories.size());
        return generateIdsInSameShard(expectedShardIndex, numIdsToBeGenerated);
    }

    private List<String> generateIdsInSameShard(int expectedShardIndex, int numIdsToBeGenerated) {
        return IntStream.range(0, numIdsToBeGenerated)
                .mapToObj(value -> {
                    while (true) {
                        String id = UUID.randomUUID().toString();
                        if (shardCalculator.shardId(id) == expectedShardIndex) {
                            return id;
                        }
                    }
                })
                .collect(Collectors.toList());
    }
}