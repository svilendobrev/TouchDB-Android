package com.couchbase.touchdb.testapp.tests;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import junit.framework.Assert;

import com.fasterxml.jackson.databind.ObjectMapper;

import android.test.InstrumentationTestCase;
import android.util.Base64;
import android.util.Log;

import com.couchbase.touchdb.TDBody;
import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDServer;
import com.couchbase.touchdb.router.TDRouter;
import com.couchbase.touchdb.router.TDURLConnection;
import com.couchbase.touchdb.router.TDURLStreamHandlerFactory;
import com.couchbase.touchdb.support.FileDirUtils;

public abstract class TouchDBTestCase extends InstrumentationTestCase {

    public static final String TAG = "TouchDBTestCase";

    private static boolean initializedUrlHandler = false;

    protected ObjectMapper mapper = new ObjectMapper();

    protected TDServer server = null;
    protected TDDatabase database = null;
    protected String DEFAULT_TEST_DB = "touchdb-test";

    @Override
    protected void setUp() throws Exception {
        Log.v(TAG, "setUp");
        super.setUp();

        //for some reason a traditional static initializer causes junit to die
        if(!initializedUrlHandler) {
            TDURLStreamHandlerFactory.registerSelfIgnoreError();
            initializedUrlHandler = true;
        }

        loadCustomProperties();
        startTouchDB();
        startDatabase();
    }

    protected String getServerPath() {
        String filesDir = getInstrumentation().getContext().getFilesDir().getAbsolutePath();
        return filesDir;
    }

    protected void startTouchDB() {
        try {
            String serverPath = getServerPath();
            File serverPathFile = new File(serverPath);
            FileDirUtils.deleteRecursive(serverPathFile);
            serverPathFile.mkdir();
            server = new TDServer(getServerPath());
        } catch (IOException e) {
            fail("Creating server caused IOException");
        }
    }

    protected void stopTouchDB() {
        if(server != null) {
            server.close();
        }
    }

    protected void startDatabase() {
        database = ensureEmptyDatabase(DEFAULT_TEST_DB);
        boolean status = database.open();
        Assert.assertTrue(status);
    }

    protected void stopDatabse() {
        if(database != null) {
            database.close();
        }
    }

    protected TDDatabase ensureEmptyDatabase(String dbName) {
        TDDatabase db = server.getExistingDatabaseNamed(dbName);
        if(db != null) {
            boolean status = db.deleteDatabase();
            Assert.assertTrue(status);
        }
        db = server.getDatabaseNamed(dbName, true);
        return db;
    }

    protected void loadCustomProperties() throws IOException {
        Properties systemProperties = System.getProperties();
        InputStream mainProperties = TouchDBTestCase.class.getResourceAsStream("test.properties");
        if(mainProperties != null) {
            systemProperties.load(mainProperties);
        }
        InputStream localProperties = TouchDBTestCase.class.getResourceAsStream("local-test.properties");
        if(localProperties != null) {
            systemProperties.load(localProperties);
        }
    }

    protected String getReplicationProtocol() {
        return System.getProperty("replicationProtocol");
    }

    protected String getReplicationServer() {
        return System.getProperty("replicationServer");
    }

    protected int getReplicationPort() {
        return Integer.parseInt(System.getProperty("replicationPort"));
    }

    protected String getReplicationAdminUser() {
        return System.getProperty("replicationAdminUser");
    }

    protected String getReplicationAdminPassword() {
        return System.getProperty("replicationAdminPassword");
    }

    protected String getReplicationDatabase() {
        return System.getProperty("replicationDatabase");
    }

    protected URL getReplicationURL() throws MalformedURLException {
        if(getReplicationAdminUser() != null) {
            return new URL(String.format("%s://%s:%s@%s:%d/%s", getReplicationProtocol(), getReplicationAdminUser(), getReplicationAdminPassword(), getReplicationServer(), getReplicationPort(), getReplicationDatabase()));
        } else {
            return new URL(String.format("%s://%s:%d/%s", getReplicationProtocol(), getReplicationServer(), getReplicationPort(), getReplicationDatabase()));
        }
    }

    protected URL getReplicationURLWithoutCredentials() throws MalformedURLException {
        return new URL(String.format("%s://%s:%d/%s", getReplicationProtocol(), getReplicationServer(), getReplicationPort(), getReplicationDatabase()));
    }

    @Override
    protected void tearDown() throws Exception {
        Log.v(TAG, "tearDown");
        super.tearDown();
        stopDatabse();
        stopTouchDB();
    }

    protected Map<String,Object> userProperties(Map<String,Object> properties) {
        Map<String,Object> result = new HashMap<String,Object>();

        for (String key : properties.keySet()) {
            if(!key.startsWith("_")) {
                result.put(key, properties.get(key));
            }
        }

        return result;
    }

    protected  void deleteRemoteDB(URL url) {
        try {
            Log.v(TAG, String.format("Deleting %s", url.toExternalForm()));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            String userInfo = url.getUserInfo();
            if(userInfo != null) {
                byte[] authEncBytes = Base64.encode(userInfo.getBytes(), Base64.DEFAULT);

                conn.setRequestProperty("Authorization", "Basic " + new String(authEncBytes));
            }

            conn.setRequestMethod("DELETE");
            conn.connect();
            int responseCode = conn.getResponseCode();
            Assert.assertTrue(responseCode < 300 || responseCode == 404);
        } catch (Exception e) {
            Log.e(TAG, "Exceptiong deleting remote db", e);
        }
    }

    protected TDURLConnection sendRequest(TDServer server, String method, String path, Map<String,String> headers, Object bodyObj) {
        try {
            URL url = new URL("touchdb://" + path);
            TDURLConnection conn = (TDURLConnection)url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod(method);
            if(headers != null) {
                for (String header : headers.keySet()) {
                    conn.setRequestProperty(header, headers.get(header));
                }
            }
            Map<String, List<String>> allProperties = conn.getRequestProperties();
            if(bodyObj != null) {
                conn.setDoInput(true);
                ByteArrayInputStream bais = new ByteArrayInputStream(mapper.writeValueAsBytes(bodyObj));
                conn.setRequestInputStream(bais);
            }

            TDRouter router = new TDRouter(server, conn);
            router.start();
            return conn;
        } catch (MalformedURLException e) {
            fail();
        } catch(IOException e) {
            fail();
        }
        return null;
    }

    protected Object parseJSONResponse(TDURLConnection conn) {
        Object result = null;
        TDBody responseBody = conn.getResponseBody();
        if(responseBody != null) {
            byte[] json = responseBody.getJson();
            String jsonString = null;
            if(json != null) {
                jsonString = new String(json);
                try {
                    result = mapper.readValue(jsonString, Object.class);
                } catch (Exception e) {
                    fail();
                }
            }
        }
        return result;
    }

    protected Object sendBody(TDServer server, String method, String path, Object bodyObj, int expectedStatus, Object expectedResult) {
        TDURLConnection conn = sendRequest(server, method, path, null, bodyObj);
        Object result = parseJSONResponse(conn);
        Log.v(TAG, String.format("%s %s --> %d", method, path, conn.getResponseCode()));
        Assert.assertEquals(expectedStatus, conn.getResponseCode());
        if(expectedResult != null) {
            Assert.assertEquals(expectedResult, result);
        }
        return result;
    }

    protected Object send(TDServer server, String method, String path, int expectedStatus, Object expectedResult) {
        return sendBody(server, method, path, null, expectedStatus, expectedResult);
    }

}
