package org.roboy.memory.util;

import com.google.gson.Gson;
import org.neo4j.driver.internal.types.InternalTypeSystem;
import org.neo4j.driver.v1.*;
import redis.clients.jedis.Jedis;

import java.net.URI;
import java.util.*;
import java.util.logging.Logger;

import static org.roboy.memory.util.Config.*;

/**
 * Contains the methods for running GET, CREATE, UPDATE, REMOVE and Cypher queries.
 * Talks to the Neo4j and Redis databases.
 * Handles the result retrieved from Neo4j.
 */
public class Neo4j implements AutoCloseable {

    private static Neo4j _instance; ///< An instance of the class
    private static Driver _driver; ///< An instance of Neo4j driver
    private static Jedis jedis; ///< An instance of Jedis for Redis handling
    private static Gson parser = new Gson(); ///< An instance of Gson parser for creating JSON response
    private static Logger logger = Logger.getLogger(Neo4j.class.toString()); ///< An instance of the logger

    private Neo4j() {
        _driver = GraphDatabase.driver(NEO4J_ADDRESS, AuthTokens.basic(NEO4J_USERNAME, NEO4J_PASSWORD));
    }

    /**
     * Singleton for the Neo4j class
     *
     * @return Neo4J Driver instance if the object of Neo4j class is initialized
     */
    public static Driver getInstance() {
        if (_instance == null) {
            _instance = new Neo4j();
        }

        return _instance.getDriver();
    }

    /**
     * Getter for the Neo4j driver instance
     *
     * @return Neo4J Driver instance
     */
    private Driver getDriver() {
        return _driver;
    }

    @Override
    public void close() throws Exception {
        _driver.close();
    }

    /**
     * Wrapper for the Neo4j query parameters
     *
     * @return Set of keys and values for parameters
     */
    public static Value parameters(Object... keysAndValues) {
        return Values.parameters(keysAndValues);
    }

    /**
     * Method to channel a plain Cypher query to Neo4j
     *
     * @param query formed in Cypher
     * @return plain response from Neo4j
     */
    public static String run(String query) {
        try (Session session = getInstance().session()) {
            StatementResult result = session.run(query);
            String response = "";
            Value value;
            logger.info(query);
            while (result.hasNext()) {
                value = result.next().get(0);
                if (value.hasType(InternalTypeSystem.TYPE_SYSTEM.NODE())) { //if response is Node
                    response += value.asMap().toString() + ", ";
                } else { //if responce is String
                    response += value.toString() + ", ";
                }
            }
            logger.info(response);
            return response;
        }
    }


    /**
     * Method accepting JSON Create queries
     *
     * @param label is denoting a type of the node to be created
     * @param faceVector contains face features for a node of label "Person". OPTIONAL
     * @param properties is a dictionary containing properties of the node
     * @return result obtained by createNode method
     */
    public static String createNode(String label, String[] faceVector, Map<String, String> properties) {
        try (Session session = getInstance().session()) {
            return createNode(session, properties, faceVector, label);
        }
    }

    /**
     * Method processing JSON Create queries
     *
     * @param session is a session handler for transaction handling to query Neo4j DB
     * @param label is denoting a type of the node to be created
     * @param faceVector contains face features for a node of label "Person". OPTIONAL
     * @param properties is a dictionary containing properties of the node
     * @return ID of the node that was created in Neo4j DB
     */
    private static String createNode(Session session, Map<String, String> properties, String[] faceVector, String label) {
        StatementResult result = session.writeTransaction(tx -> {
            //no prepared statements for now
            String query = "CREATE (a:" + label.substring(0,1).toUpperCase() + label.substring(1).toLowerCase();
            query += "{";
            for (String key : properties.keySet()) {
                query += key + ":'" + properties.get(key).substring(0,1).toUpperCase() + properties.get(key).substring(1).toLowerCase() + "',";
            }
            query = query.substring(0, query.length() - 1);
            query += "}";
            //TODO: refactor this?
            query += ") RETURN ID(a)";
            logger.info(query);
            return  tx.run(query, parameters());
        });
        String id = "{'id': " + result.next().get(0).toString() + "}";

        if (faceVector != null) {
            jedis = new Jedis(URI.create(REDIS_URI));
            jedis.auth(REDIS_PASSWORD);
            for (String vector : faceVector) {
                jedis.sadd(id, vector);
            }
        }

        logger.info(id);
        return id;
    }


    /**
     * Method accepting JSON Update queries
     *
     * @param id is a unique pointer to the node in Neo4j DB
     * @param relations is a dictionary containing relationships of the node with other nodes
     * @param properties is a dictionary containing properties of the node
     * @return result obtained by update method
     */
    public static String updateNode(int id, Map<String, String[]> relations, Map<String, String> properties) {
        try (Session session = getInstance().session()) {
            return session.readTransaction( new TransactionWork<String>()
            {
                @Override
                public String execute( Transaction tx )
                {
                    return update( tx, id, relations, properties);
                }
            } );
        }
    }

    /**
     * Method processing JSON Update queries
     *
     * @param tx is a transaction handler to query Neo4j DB
     * @param id is a unique pointer to the node in Neo4j DB
     * @param relations is a dictionary containing relationships of the node with other nodes
     * @param properties is a dictionary containing properties of the node
     * @return response from Neo4j upon updating the node
     */
    private static String update( Transaction tx, int id, Map<String, String[]> relations, Map<String, String> properties) {

        String query = "MATCH (a)";
        String where = " WHERE ID(a)=" + id;

        if(relations != null) { //add relations
            String create = "";
            int i = 0;
            for (String key : relations.keySet()) {
                for (int j = 0; j < relations.get(key).length; j++) {
                    String relID = relations.get(key)[j];
                    query += ",(b" + i + j + ")";
                    where += " AND ID(b" + i + j + ") = " + relID;
                    create += " CREATE (a)-[r" + i + j + ":" + key.toUpperCase() +"]->(b" + i + j + ") ";
                }
                i++;
            }
            where += create;
        }

        if (properties != null) { //set properties
            String set = "";
            for (String key : properties.keySet()) {
                if (properties.get(key).matches("^[0-9]*$")) { //if property is int
                    set += " Set a." + key + " = " + properties.get(key).substring(0,1).toUpperCase() + properties.get(key).substring(1).toLowerCase(); //without ''
                } else {
                    set += " Set a." + key + " = '" + properties.get(key).substring(0,1).toUpperCase() + properties.get(key).substring(1).toLowerCase() + "'"; //just Strings, no int
                }
            }
            where += set;
        }
        query += where + " Return a";

        logger.info(query);

        StatementResult result = tx.run( query, parameters() );
        String response = "";
        if (result.hasNext()) {
            response = parser.toJson(result.next().get(0).asMap());
        }
        logger.info(response);
        return response.toLowerCase();
    }


    /**
     * Method accepting JSON Get by ID queries
     *
     * @param id is a unique pointer to the node in Neo4j DB
     * @return result obtained by matchNodeById method
     */
    public static String getNodeById(int id) {

        try (Session session = getInstance().session()) {
            return session.readTransaction( new TransactionWork<String>()
            {
                @Override
                public String execute( Transaction tx )
                {
                    return matchNodeById( tx, id );
                }
            } );
        }
    }

    /**
     * Method processing JSON Get by ID queries
     *
     * @param tx is a transaction handler to query Neo4j DB
     * @param id is a unique pointer to the node in Neo4j DB
     * @return a JSON object containing node labels, properties and relationships
     */
    private static String matchNodeById( Transaction tx, int id ) {

        String queryProperties = "MATCH (a) where ID(a)=" + id + " RETURN a";
        String queryID = "MATCH (a) where ID(a)=" + id + " RETURN ID(a)";
        String queryRelations = "MATCH (a)-[r]-(b) WHERE ID(a) = " + id +" Return type(r),ID(b)";
        String queryLabels = "MATCH (a) WHERE ID(a) = " + id +" Return labels(a)";
        HashMap<String, HashSet<String>> relAndIDs = new HashMap<String, HashSet<String>>();

        logger.info(queryID);
        logger.info(queryLabels);
        logger.info(queryProperties);
        logger.info(queryRelations);

        StatementResult result = tx.run(queryID, parameters() ); //run query
        String ID = "";
        if (result.hasNext()) {
            ID = "'id': " + result.next().get(0).toString();

            result = tx.run(queryProperties, parameters() ); //run query
            String properties = "";
            if (result.hasNext()) {
                properties = ", 'properties': " + parser.toJson(result.next().get(0).asMap());
            }

            result = tx.run(queryRelations, parameters() ); //run queryRelations
            String relationResponse = "";
            if (result.hasNext()) {
                relationResponse = ", 'relations': ";
                while (result.hasNext()) {
                    Record next = result.next();
                    String key = next.get(0).toString();
                    String value = next.get(1).toString();
                    if (!relAndIDs.containsKey(next.get(0).toString())) {
                        relAndIDs.put(key, new HashSet<String>(Arrays.asList(next.get(1).toString())));
                    } else {
                        relAndIDs.get(key).add(value);
                    }
                }
                relationResponse += relAndIDs.toString().replace('=', ':');
            }

            result = tx.run(queryLabels, parameters() ); //run query
            String labels = "";
            if (result.hasNext()) {
                labels = ", 'labels': [";
                while (result.hasNext()) {
                    String value = result.next().get(0).toString();
                    labels += value.substring(1, value.length() - 1) + ", ";
                }
                labels = labels.substring(0, labels.length() - 2) + "]";
            }


            logger.info(("{" + ID + labels + properties + relationResponse + "}").toLowerCase());
            return ("{" + ID + labels + properties + relationResponse + "}").toLowerCase();
        } else {
            return "";
        }
    }

    /**
     * Method accepting JSON Get IDs of nodes queries
     *
     * @param label is denoting a type of the nodes to be included
     * @param relations is a dictionary containing relationships of the nodes with other nodes
     * @param properties is a dictionary containing properties of the nodes
     * @return result obtained by matchNode method
     */
    public static String getNode(String label, Map<String, String[]> relations, Map<String, String> properties) {

        try (Session session = getInstance().session()) {
            return session.readTransaction( new TransactionWork<String>() {
                @Override
                public String execute( Transaction tx )
                {
                    return matchNode( tx, label, relations, properties );
                }
            } );
        }
    }

    /**
     * Method processing JSON Get IDs of nodes queries
     *
     * @param tx is a transaction handler to query Neo4j DB
     * @param label is denoting a type of the nodes to be included
     * @param relations is a dictionary containing relationships of the nodes with other nodes
     * @param properties is a dictionary containing properties of the nodes
     * @return JSON array of nodes' IDs
     */
    private static String matchNode( Transaction tx, String label, Map<String, String[]> relations, Map<String, String> properties ) {

        //MATCH (a)-[r1]-(b1)-[r2]->(b2)
        //    WHERE ID(b1) = 158 AND type(r1)=~'STUDY_AT' AND ID(b0) = 5 AND type(r0)=~ 'FRIEND_OF' AND a.name = 'Roboy' AND labels(a) = 'Robot'
        //RETURN a
        String match = "";
        String where = "";


        if (relations != null) {
            if (Objects.equals(where, "")) {
                where = " WHERE ";
            }

            int i = 0;
            for (Map.Entry<String, String[]> next : relations.entrySet()) {
                //iterate over the pairs
                for (int j = 0; j < next.getValue().length; j++) {
                    String relID = next.getValue()[j];
                    if (i < relations.size() - 1) {
                        match += "-[r" + i + j + "]-(b" + i + j + ")";
                    } else {
                        match += "-[r" + i + j + "]->(b" + i + j + ")";
                    }
                    where += "type(r" + i + j + ")=~ '" + next.getKey().toUpperCase();
                    where += "' AND ID(b" + i + j + ") = " + relID;
                }

                where += " AND ";

                i++;
            }
            where = where.substring(0, where.length() - 4);
        }

        if (properties != null) {
            if (Objects.equals(where, "")) {
                where = " WHERE ";
            } else {
                where += "AND ";
            }

            for (Map.Entry<String, String> next : properties.entrySet()) {
                //iterate over the pairs
                if (next.getValue().matches("^[0-9]*$")) {
                    where += "a." + next.getKey() + " = " + next.getValue().substring(0,1).toUpperCase() + next.getValue().substring(1).toLowerCase() + " AND ";
                } else {
                    where += "a." + next.getKey() + " = '" + next.getValue().substring(0,1).toUpperCase() + next.getValue().substring(1).toLowerCase() + "' AND ";
                }
            }
            where = where.substring(0, where.length() - 5);
        }
        String query = "MATCH (a";
        if (label != null) {
            query += ":" + label;
        }

        query += ")" + match + where + " RETURN ID(a)";
        logger.info(query);
        StatementResult result = tx.run(query, parameters() );
        String response = "";
        while (result.hasNext()) {
            response += result.next().get(0).toString() + ", ";
        }
        if (!Objects.equals(response, "")) response = response.substring(0, response.length() - 2);
        response = "{'id':[" + response + "]}";
        logger.info(response);
        return response.toLowerCase();
    }

    /**
     * Method accepting JSON Remove queries
     *
     * @param id of the node which relations and properties need to be removed
     * @param relations is a dictionary containing relationships of the node with other nodes
     * @param properties is a dictionary containing properties of the node
     * @return result obtained by removeRelsProps method
     */
    public static String remove(int id, Map<String, String[]> relations, String[] properties) {
        try (Session session = getInstance().session()) {
            return session.readTransaction( new TransactionWork<String>()
            {
                @Override
                public String execute( Transaction tx )
                {
                    return removeRelsProps( tx, id, relations, properties);
                }
            } );
        }
    }

    /**
     * Method processing JSON Remove queries
     *
     * @param tx is a transaction handler to query Neo4j DB
     * @param id of the node which relations and properties need to be removed
     * @param relations is a dictionary containing relationships of the node with other nodes
     * @param properties is a dictionary containing properties of the node
     * @return response from Neo4j upon removing the specified relations and properties
     */
    private static String removeRelsProps( Transaction tx, int id, Map<String, String[]> relations, String[] properties) {

        String query = "";
        String where = " WHERE ID(a)=" + id;
        String delete = "";
        String remove = "";


        //Match (n)-[r1:LIVE_IN]->(b1),(n)-[r2:LIVE_IN]->(b2) where ID(n)=131 AND ID(b1)=78 AND ID(b2)=57 Delete r1,r2 Remove n.abc,n.xyz
        if(relations != null) { //delete relations
            query = "MATCH ";
            delete = " Delete ";
            int i = 0;
            for (String key : relations.keySet()) {
                for (int j = 0; j < relations.get(key).length; j++) {
                    String relID = relations.get(key)[j];
                    delete += "r" + i + j + ",";
                    where +=  " AND ID(b" + i + j + ") = " + relID;
                    query += "(a)-[r" + i + j + ":" + key.toUpperCase() +"]->(b" + i + j + "), ";
                }
                i++;
            }
            query = query.substring(0,query.length()-2);
            delete = delete.substring(0,delete.length()-1);
        }

        //Match n where ID(n)=1 REMOVE n.key
        if (properties != null) { //delete properties
            if (Objects.equals(query, "")) {
                query = "MATCH (a) ";
            }
            remove = " Remove ";
            for (String key : properties) {
                if (!Objects.equals(key, "name")) {
                    remove += "a." + key + ", ";
                }
            }
            remove = remove.substring(0,remove.length()-2);
        }

        query += where + delete + remove;

        logger.info(query);

        StatementResult result = tx.run( query, parameters() );
        logger.info(result.toString());
        return result.toString().toLowerCase();
    }

}
