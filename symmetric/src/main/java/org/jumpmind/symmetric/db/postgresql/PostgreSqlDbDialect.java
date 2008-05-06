/*
 * SymmetricDS is an open source database synchronization solution.
 *   
 * Copyright (C) Eric Long <erilong@users.sourceforge.net>
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

package org.jumpmind.symmetric.db.postgresql;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jumpmind.symmetric.db.AbstractDbDialect;
import org.jumpmind.symmetric.db.IDbDialect;
import org.jumpmind.symmetric.model.Trigger;

public class PostgreSqlDbDialect extends AbstractDbDialect implements IDbDialect {

    static final Log logger = LogFactory.getLog(PostgreSqlDbDialect.class);

    static final String TRANSACTION_ID_EXPRESSION = "txid_current()";

    static final String SYNC_TRIGGERS_DISABLED_USER_VARIABLE = "explain_pretty_print";

    private String transactionIdExpression = "null";
    
    protected void initForSpecificDialect() {
        if (getMajorVersion() >= 8 && getMinorVersion() >= 3) {
            logger.info("Enabling transaction ID support");
            transactionIdExpression = TRANSACTION_ID_EXPRESSION;
        }
    }
    
    protected boolean allowsNullForIdentityColumn() {
        return false;
    }

    @Override
    protected boolean doesTriggerExistOnPlatform(String catalogName, String schema, String tableName, String triggerName) {
        schema = schema == null ? (getDefaultSchema() == null ? null : getDefaultSchema()) : schema;
        String checkSchema = (schema != null && schema.length() > 0) ? " and trigger_schema = '"
                + schema + "'" : "";
        return jdbcTemplate.queryForInt(
                        "select count(*) from information_schema.triggers where trigger_name like ? and event_object_table like ?"
                                + checkSchema, new Object[] { triggerName.toLowerCase(), tableName.toLowerCase() }) > 0;
    }

    public void removeTrigger(String schemaName, String triggerName) {
        throw new RuntimeException("Not implemented.  Use removeTrigger(schema, trigger, table) instead.");
    }
    
    public void removeTrigger(String catalogName, String schemaName, String triggerName, String tableName) {
        schemaName = schemaName == null ? "" : (schemaName + ".");
        try {
            jdbcTemplate.update("drop trigger " + schemaName + triggerName + " on " + tableName);
            jdbcTemplate.update("drop function " + schemaName + "f" + triggerName + "()");
        } catch (Exception e) {
            logger.warn("Trigger does not exist");
        }
    }

    public void disableSyncTriggers() {
        jdbcTemplate.update("set " + SYNC_TRIGGERS_DISABLED_USER_VARIABLE + " to off");
    }

    public void enableSyncTriggers() {
        jdbcTemplate.update("set " + SYNC_TRIGGERS_DISABLED_USER_VARIABLE + " to on");
    }

    public String getSyncTriggersExpression() {
        return "current_setting('" + SYNC_TRIGGERS_DISABLED_USER_VARIABLE + "') = 'on'";
    }

    public String getTransactionTriggerExpression(Trigger trigger) {
        return transactionIdExpression;
    }
    
    public String getSelectLastInsertIdSql(String sequenceName) {
        return "select currval('" + sequenceName + "_seq')";
    }

    public boolean requiresSavepointForFallback() {
        return true;
    }

    public boolean isCharSpacePadded() {
        return true;
    }

    public boolean isCharSpaceTrimmed() {
        return false;
    }

    public boolean isEmptyStringNulled() {
        return false;
    }

    public boolean storesLowerCaseNamesInCatalog() {
        return true;
    }

    public void purge() {
    }
    
    public String getDefaultCatalog() {
        return null;
    }    

    public String getDefaultSchema() {
        return null;
    }
}
