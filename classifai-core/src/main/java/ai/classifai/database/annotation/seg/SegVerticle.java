/*
 * Copyright (c) 2020-2021 CertifAI Sdn. Bhd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.classifai.database.annotation.seg;

import ai.classifai.database.DbConfig;
import ai.classifai.database.annotation.AnnotationVerticle;
import ai.classifai.util.ParamConfig;
import ai.classifai.util.message.ErrorCodes;
import ai.classifai.util.type.AnnotationType;
import ai.classifai.util.type.database.H2;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Segmentation Verticle
 *
 * @author codenamewei
 */
@Slf4j
public class SegVerticle extends AnnotationVerticle
{
    @Getter private static JDBCPool jdbcPool;

    public void onMessage(Message<JsonObject> message)
    {
        if (!message.headers().contains(ParamConfig.getActionKeyword()))
        {
            log.error("No action header specified for message with headers {} and body {}",
                    message.headers(), message.body().encodePrettily());

            message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal(), "No keyword " + ParamConfig.getActionKeyword() + " specified");
            return;
        }
        String action = message.headers().get(ParamConfig.getActionKeyword());

        if (action.equals(SegDbQuery.retrieveData()))
        {
            this.retrieveData(message, jdbcPool, SegDbQuery.retrieveData(), AnnotationType.SEGMENTATION);
        }
        else if (action.equals(SegDbQuery.retrieveDataPath()))
        {
            this.retrieveDataPath(message, jdbcPool, SegDbQuery.retrieveDataPath());
        }
        else if (action.equals(SegDbQuery.updateData()))
        {
            this.updateData(message, jdbcPool, SegDbQuery.updateData(), AnnotationType.SEGMENTATION);
        }
        else if (action.equals(SegDbQuery.loadValidProjectUUID()))
        {
            this.loadValidProjectUUID(message, jdbcPool, SegDbQuery.loadValidProjectUUID());
        }
        else if (action.equals(SegDbQuery.deleteProjectUUIDListwithProjectID()))
        {
            this.deleteProjectUUIDListwithProjectID(message, jdbcPool, SegDbQuery.deleteProjectUUIDListwithProjectID());
        }
        else if (action.equals(SegDbQuery.deleteProjectUUIDList()))
        {
            this.deleteProjectUUIDList(message, jdbcPool, SegDbQuery.deleteProjectUUIDList());
        }
        else
        {
            log.error("SegVerticle query error. Action did not have an assigned function for handling.");
        }
    }


    @Override
    public void stop(Promise<Void> promise) throws Exception
    {
        jdbcPool.close();

        log.info("Seg Verticle stopping...");
    }

    //obtain a JDBC client connection,
    //Performs a SQL query to create the pages table unless it already existed
    @Override
    public void start(Promise<Void> promise) throws Exception
    {
        H2 h2 = DbConfig.getH2();

        jdbcPool = JDBCPool.pool(vertx,  new JDBCConnectOptions()
                .setJdbcUrl(h2.getUrlHeader() + DbConfig.getTableAbsPathDict().get(DbConfig.getSegKey()))
                .setUser(h2.getUser())
                .setPassword(h2.getPassword())
                ,new PoolOptions().setMaxSize(30)
        );

        jdbcPool.getConnection(ar -> {

            if (ar.failed())
            {
                log.error("Could not open a database connection for SegVerticle", ar.cause());
                promise.fail(ar.cause());

            }
            else
            {
                SqlConnection connection = ar.result();
                connection.query(SegDbQuery.createProject())
                .execute()
                .onComplete(create -> {
                        connection.close();
                        if (create.succeeded())
                        {
                            log.error("SegVerticle database preparation error", create.cause());
                            promise.fail(create.cause());

                        }
                        else
                        {
                            //the consumer methods registers an event bus destination handler
                            vertx.eventBus().consumer(SegDbQuery.getQueue(), this::onMessage);
                            promise.complete();
                        }
                });
            }
        });
    }
}