/*
 * SymmetricDS is an open source database synchronization solution.
 *   
 * Copyright (C) Chris Henson <chenson42@users.sourceforge.net>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.jumpmind.symmetric;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jumpmind.symmetric.common.PropertiesConstants;
import org.jumpmind.symmetric.common.TestConstants;
import org.jumpmind.symmetric.load.DataLoaderTest;
import org.jumpmind.symmetric.service.impl.DataLoaderServiceTest;
import org.testng.annotations.Factory;

/**
 * Run this test to run all the tests against all the configured databases.
 */
public class MultiDatabaseTest {

    private static final Log logger = LogFactory.getLog(MultiDatabaseTest.class);

    enum DatabaseRole {
        CLIENT, ROOT
    };

    public String[][] getClientAndRootCombos() {

        Properties properties = getTestProperties();
        String[] clientDatabaseTypes = StringUtils.split(properties.getProperty("test.client"), ",");
        String[] rootDatabaseTypes = getRootDbTypes();

        String[][] clientAndRootCombos = new String[rootDatabaseTypes.length * clientDatabaseTypes.length - 1][2];

        int index = 0;
        // skip the first because it will be covered in the normal run of the integration tests
        boolean skipFirst = true;
        for (String rootDatabaseType : rootDatabaseTypes) {
            for (String clientDatabaseType : clientDatabaseTypes) {
                if (!skipFirst) {
                    clientAndRootCombos[index][0] = clientDatabaseType;
                    clientAndRootCombos[index++][1] = rootDatabaseType;
                } else {
                    skipFirst = false;
                }
            }
        }
        return clientAndRootCombos;

    }

    public String[] getRootDbTypes() {
        Properties properties = getTestProperties();
        return StringUtils.split(properties.getProperty("test.root"), ",");
    }

    @Factory
    public Object[] createTests() throws Exception {
        List<Object> tests2Run = new ArrayList<Object>();

        String[] rootDbTypes = getRootDbTypes();

        logger.info(rootDbTypes[0] + " will be tested when the individual unit tests are run.");

        for (int i = 1; i < rootDbTypes.length; i++) {
            String dbType = rootDbTypes[i];
            logger.info("Tests are being dynamically added for the " + dbType + " database.");
            tests2Run.addAll(createDatabaseTests(dbType));
        }

        String[][] clientAndRootCombos = getClientAndRootCombos();
        for (String[] objects : clientAndRootCombos) {
            logger.info("Integration tests are being dynamically added for the " + objects[0]
                    + " client and " + objects[1] + " root combination of databases.");
            tests2Run.addAll(createIntegrationTests(objects[0], objects[1]));
        }

        return tests2Run.toArray(new Object[tests2Run.size()]);
    }

    public List<? extends AbstractTest> createIntegrationTests(String clientDatabaseType,
            String rootDatabaseType) throws Exception {
        List<AbstractIntegrationTest> tests2Run = new ArrayList<AbstractIntegrationTest>();
        tests2Run.add(new IntegrationTest(clientDatabaseType, rootDatabaseType));
        return tests2Run;
    }

    public List<? extends AbstractTest> createDatabaseTests(String rootDatabaseType) throws Exception {
        List<AbstractDatabaseTest> tests2Run = new ArrayList<AbstractDatabaseTest>();
        tests2Run.add(new DataLoaderServiceTest(rootDatabaseType));
        tests2Run.add(new DataLoaderTest(rootDatabaseType));
        tests2Run.add(new CrossCatalogSyncTest(rootDatabaseType));        
        return tests2Run;
    }

    protected static File writeTempPropertiesFileFor(String databaseType, DatabaseRole databaseRole) {
        try {
            Properties properties = getTestProperties();
            Properties newProperties = new Properties();
            Set<Object> keys = properties.keySet();
            for (Object string : keys) {
                String key = (String) string;
                String dbRoleReplaceToken = databaseType + "." + databaseRole.name().toLowerCase() + ".";
                if (key.startsWith(dbRoleReplaceToken)) {
                    String newKey = key.substring(dbRoleReplaceToken.length());
                    newProperties.put(newKey, properties.get(key));
                } else if (key.startsWith(databaseType)) {
                    String newKey = key.substring(databaseType.length() + 1);
                    newProperties.put(newKey, properties.get(key));
                } else {
                    newProperties.put(key, properties.get(key));
                }
            }

            if (isConnectionValid(newProperties)) {
                newProperties.setProperty("symmetric.runtime.group.id",
                        databaseRole == DatabaseRole.CLIENT ? TestConstants.TEST_CLIENT_NODE_GROUP
                                : TestConstants.TEST_ROOT_NODE_GROUP);
                newProperties.setProperty("symmetric.runtime.external.id",
                        databaseRole == DatabaseRole.ROOT ? TestConstants.TEST_ROOT_EXTERNAL_ID
                                : TestConstants.TEST_CLIENT_EXTERNAL_ID);
                newProperties.setProperty(PropertiesConstants.START_RUNTIME_MY_URL, "internal://"
                        + databaseRole.name().toLowerCase());
                newProperties.setProperty(PropertiesConstants.START_RUNTIME_REGISTRATION_URL,
                        databaseRole == DatabaseRole.CLIENT ? ("internal://" + DatabaseRole.ROOT.name()
                                .toLowerCase()) : "");
                newProperties.setProperty(PropertiesConstants.START_RUNTIME_ENGINE_NAME, databaseRole.name().toLowerCase());

                File propertiesFile = File.createTempFile("symmetric-test.", ".properties");
                FileOutputStream os = new FileOutputStream(propertiesFile);
                newProperties.store(os, "generated by the symmetricds unit tests");
                os.close();
                propertiesFile.deleteOnExit();
                return propertiesFile;

            } else {
                logger.error("Could not find a valid connection for " + databaseType);
                return null;
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

    }

    private static boolean isConnectionValid(Properties properties) throws Exception {
        try {
            Class.forName(properties.getProperty("db.driver"));
            Connection c = DriverManager.getConnection(properties.getProperty("db.url"), properties
                    .getProperty("db.user"), properties.getProperty("db.password"));
            c.close();
            return true;
        } catch (Exception ex) {
            logger.error("Could not connect to the test database using the url: "
                    + properties.getProperty("db.url") + " and classpath: "
                    + System.getProperty("java.class.path"), ex);
            return false;
        }
    }

    protected static Properties getTestProperties() {
        try {
            final String TEST_PROPERTIES_FILE = "/symmetric-test.properties";
            Properties properties = new Properties();

            properties.load(MultiDatabaseTest.class.getResourceAsStream(TEST_PROPERTIES_FILE));
            String homeDir = System.getProperty("user.home");
            File propertiesFile = new File(homeDir + TEST_PROPERTIES_FILE);
            if (propertiesFile.exists()) {
                FileInputStream f = new FileInputStream(propertiesFile);
                properties.load(f);
                f.close();
            } else {
                logger.info("Could not find " + propertiesFile.getAbsolutePath()
                        + ". Using all of the default properties");
            }
            return properties;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

}
