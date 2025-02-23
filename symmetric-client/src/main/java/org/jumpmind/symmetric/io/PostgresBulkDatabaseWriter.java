/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.io;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.DatabaseInfo;
import org.jumpmind.db.platform.IDatabasePlatform;
import org.jumpmind.db.sql.JdbcSqlTransaction;
import org.jumpmind.db.util.BinaryEncoding;
import org.jumpmind.symmetric.common.ContextConstants;
import org.jumpmind.symmetric.csv.CsvWriter;
import org.jumpmind.symmetric.io.data.Batch;
import org.jumpmind.symmetric.io.data.CsvData;
import org.jumpmind.symmetric.io.data.CsvUtils;
import org.jumpmind.symmetric.io.data.DataContext;
import org.jumpmind.symmetric.io.data.DataEventType;
import org.jumpmind.symmetric.io.data.writer.DataWriterStatisticConstants;
import org.jumpmind.symmetric.io.data.writer.DatabaseWriterSettings;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

public class PostgresBulkDatabaseWriter extends AbstractBulkDatabaseWriter {

    protected int maxRowsBeforeFlush;

    protected CopyManager copyManager;

    protected CopyIn copyIn;

    protected int loadedRows = 0;

    protected boolean needsBinaryConversion;
    
    public PostgresBulkDatabaseWriter(IDatabasePlatform symmetricPlatform,
			IDatabasePlatform targetPlatform, String tablePrefix, DatabaseWriterSettings settings,
			int maxRowsBeforeFlush) {
        super(symmetricPlatform, targetPlatform, tablePrefix, settings);
        this.maxRowsBeforeFlush = maxRowsBeforeFlush;
    }
    
    @Override
    protected void bulkWrite(CsvData data) {

        statistics.get(batch).startTimer(DataWriterStatisticConstants.LOADMILLIS);

        DataEventType dataEventType = data.getDataEventType();

        if (targetTable != null || dataEventType.equals(DataEventType.CREATE)) {
        	needsBinaryConversion = false;
            if (!batch.getBinaryEncoding().equals(BinaryEncoding.NONE) && targetTable != null) {
                for (Column column : targetTable.getColumns()) {
                    if (column.isOfBinaryType()) {
                        needsBinaryConversion = true;
                        break;
                    }
                }
            }
            switch (dataEventType) {
                case INSERT:
                	startCopy();
                    try {
                        String[] parsedData = data.getParsedData(CsvData.ROW_DATA);
                        if (needsBinaryConversion) {
                            Column[] columns = targetTable.getColumns();
                            for (int i = 0; i < columns.length; i++) {
                                if (columns[i].isOfBinaryType() && parsedData[i] != null) {
                                    if (batch.getBinaryEncoding().equals(BinaryEncoding.HEX)) {
                                        parsedData[i] = encode(Hex.decodeHex(parsedData[i].toCharArray()));
                                    } else if (batch.getBinaryEncoding().equals(BinaryEncoding.BASE64)) {
                                        parsedData[i] = encode(Base64.decodeBase64(parsedData[i].getBytes()));
                                    }
                                }
                            }
                        }
                        String formattedData = CsvUtils.escapeCsvData(parsedData, '\n', '\'', CsvWriter.ESCAPE_MODE_DOUBLED);
                        formattedData = removeIllegalCharacters(formattedData);
                        byte[] dataToLoad = formattedData.getBytes();
                        copyIn.writeToCopy(dataToLoad, 0, dataToLoad.length);
                        loadedRows++;
                    } catch (Exception ex) {
                        throw getPlatform().getSqlTemplate().translate(ex);
                    }
                    //endCopy();
                    break;
                case UPDATE:
                case DELETE:
                default:
                    endCopy();
                    context.put(ContextConstants.CONTEXT_BULK_WRITER_TO_USE, "default");
                    super.write(data);
                    break;
            } 
    
            if (loadedRows >= maxRowsBeforeFlush) {
                flush();
                loadedRows = 0;
            }
        } 
        statistics.get(batch).stopTimer(DataWriterStatisticConstants.LOADMILLIS);
        statistics.get(batch).increment(DataWriterStatisticConstants.ROWCOUNT);
        statistics.get(batch).increment(DataWriterStatisticConstants.LINENUMBER);
    }

    protected String removeIllegalCharacters(String formattedData) {
        StringBuilder buff = new StringBuilder(formattedData.length());
        for (char c : formattedData.toCharArray()) {
            if (c > 0) {
                buff.append(c);
            }
        }
        return buff.toString();
    }

    protected void flush() {
        if (copyIn != null) {
            try {
                if (copyIn.isActive()) {
                    copyIn.flushCopy();
                }
            } catch (SQLException ex) {
                throw getPlatform().getSqlTemplate().translate(ex);
            }
        }
    }

    @Override
    public void open(DataContext context) {
        super.open(context);
        try {
            JdbcSqlTransaction jdbcTransaction = (JdbcSqlTransaction) getTargetTransaction();
            Connection conn = jdbcTransaction.getConnection().unwrap(org.postgresql.jdbc.PgConnection.class);
            copyManager = new CopyManager((BaseConnection) conn);
        } catch (Exception ex) {
            throw getPlatform().getSqlTemplate().translate(ex);
        }
    }

    protected void startCopy() {
        if (copyIn == null && targetTable != null) {            
            try {
                String sql = createCopyMgrSql();
                if (log.isDebugEnabled()) {
                    log.debug("starting bulk copy using: {}", sql);
                }
                copyIn = copyManager.copyIn(sql);
            } catch (Exception ex) {
                throw getPlatform().getSqlTemplate().translate(ex);
            }
        }
    }

    protected void endCopy() {
        if (copyIn != null) {
            try {
                flush();
            } finally {
                try {
                    if (copyIn.isActive()) {
                        copyIn.endCopy();
                    }
                } catch (Exception ex) {
                    statistics.get(batch).set(DataWriterStatisticConstants.ROWCOUNT, 0);
                    statistics.get(batch).set(DataWriterStatisticConstants.LINENUMBER, 0);
                   
                    throw getPlatform().getSqlTemplate().translate(ex);
                } finally {
                    copyIn = null;
                }
            }
        }
    }
    
    @Override
    public boolean start(Table table) {
        return super.start(table);
    }

    @Override
    public void end(Table table) {
        try {
            endCopy();
        } finally {
            super.end(table);
        }
    }

    @Override
    public void end(Batch batch, boolean inError) {
        if (inError && copyIn != null) {
            try {
                copyIn.cancelCopy();
            } catch (SQLException e) {
            } finally {
                copyIn = null;
            }
        }
        super.end(batch, inError);
    }

    private String createCopyMgrSql() {
        StringBuilder sql = new StringBuilder("COPY ");
        DatabaseInfo dbInfo = getPlatform().getDatabaseInfo();
        String quote = dbInfo.getDelimiterToken();
        String catalogSeparator = dbInfo.getCatalogSeparator();
        String schemaSeparator = dbInfo.getSchemaSeparator();
        sql.append(targetTable.getQualifiedTableName(quote, catalogSeparator, schemaSeparator));
        sql.append("(");
        Column[] columns = targetTable.getColumns();

        for (Column column : columns) {
            String columnName = column.getName();
            if (StringUtils.isNotBlank(columnName)) {
                sql.append(quote);
                sql.append(columnName);
                sql.append(quote);
                sql.append(",");
            }
        }
        sql.replace(sql.length() - 1, sql.length(), ")");
        sql.append("FROM STDIN with delimiter ',' csv quote ''''");
        return sql.toString();
    }
    
    protected String encode(byte[] byteData) {
        StringBuilder sb = new StringBuilder();
        for (byte b : byteData) {
            int i = b & 0xff;
            if (i >= 0 && i <= 7) {
                sb.append("\\00").append(Integer.toString(i, 8));
            } else if (i >= 8 && i <= 31) {
                sb.append("\\0").append(Integer.toString(i, 8));
            } else if (i == 92 || i >= 127) {
                sb.append("\\").append(Integer.toString(i, 8));
            } else {
                sb.append(Character.toChars(i));
            }
        }
        return sb.toString();
    }

}
