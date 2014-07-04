/*
 *
 *  * Copyright 2010-2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *  
 */

package com.orientechnologies.orient.etl.extractor;

import com.orientechnologies.orient.core.command.OBasicCommandContext;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.etl.OETLProcessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Created by luca on 04/07/14.
 */
public class OJDBCExtractor extends OAbstractExtractor {
  protected long         progress    = -1;
  protected long         total       = -1;

  protected String       url;
  protected String       userName;
  protected String       userPassword;
  protected String       query;
  protected String       queryCount;

  protected String       driverClass;
  protected Connection   conn;
  protected Statement    stm;
  protected ResultSet    rs;
  protected boolean      didNext     = false;
  protected boolean      hasNext     = false;
  protected int          rsColumns;
  protected List<String> columnNames = null;
  protected List<OType>  columnTypes = null;

  @Override
  public void configure(OETLProcessor iProcessor, ODocument iConfiguration, OBasicCommandContext iContext) {
    super.configure(iProcessor, iConfiguration, iContext);

    driverClass = resolveVariable((String) iConfiguration.field("driver"));
    url = resolveVariable((String) iConfiguration.field("url"));
    userName = resolveVariable((String) iConfiguration.field("userName"));
    userPassword = resolveVariable((String) iConfiguration.field("userPassword"));
    query = resolveVariable((String) iConfiguration.field("query"));
    queryCount = resolveVariable((String) iConfiguration.field("queryCount"));

    try {
      Class.forName(driverClass).newInstance();
    } catch (Exception e) {
      throw new OConfigurationException("JDBC Driver " + driverClass + " not found", e);
    }

    try {
      conn = DriverManager.getConnection(url, userName, userPassword);
    } catch (Exception e) {
      throw new OConfigurationException("Error on connecting to JDBC url '" + url + "' using user '" + userName
          + "' and the password provided", e);
    }
  }

  @Override
  public void begin() {
    try {
      stm = conn.createStatement();
      if (queryCount != null) {
        // GET THE TOTAL COUNTER
        final ResultSet countRs = stm.executeQuery(query);
        try {
          if (countRs != null && countRs.next())
            total = countRs.getInt(0);
        } finally {
          if (countRs != null)
            try {
              countRs.close();
            } catch (SQLException e) {
            }
        }
      }

      rs = stm.executeQuery(query);
      rsColumns = rs.getMetaData().getColumnCount();
      columnNames = new ArrayList<String>(rsColumns);
      columnTypes = new ArrayList<OType>(rsColumns);

      for (int i = 1; i <= rsColumns; ++i) {
        final String colName = rs.getMetaData().getColumnName(i);
        columnNames.add(colName);

        OType type = OType.ANY;
        final int sqlType = rs.getMetaData().getColumnType(i);
        switch (sqlType) {
        case Types.BOOLEAN:
          type = OType.BOOLEAN;
          break;
        case Types.SMALLINT:
          type = OType.SHORT;
          break;
        case Types.INTEGER:
          type = OType.INTEGER;
          break;
        case Types.FLOAT:
          type = OType.FLOAT;
          break;
        case Types.DOUBLE:
          type = OType.DOUBLE;
          break;
        case Types.BIGINT:
          type = OType.LONG;
          break;
        case Types.DECIMAL:
          type = OType.DECIMAL;
          break;
        case Types.DATE:
          type = OType.DATE;
          break;
        case Types.TIMESTAMP:
          type = OType.DATETIME;
          break;
        case Types.VARCHAR:
        case Types.LONGNVARCHAR:
        case Types.LONGVARCHAR:
          type = OType.STRING;
          break;
        case Types.BINARY:
        case Types.BLOB:
          type = OType.BINARY;
          break;
        case Types.CHAR:
        case Types.TINYINT:
          type = OType.BYTE;
          break;
        }
        columnTypes.add(type);
      }

    } catch (SQLException e) {
      throw new OExtractorException(getName() + ": error on executing query '" + query + "'", e);
    }
  }

  @Override
  public void end() {
    if (rs != null)
      try {
        rs.close();
      } catch (SQLException e) {
      }
    if (stm != null)
      try {
        stm.close();
      } catch (SQLException e) {
      }
    if (conn != null)
      try {
        conn.close();
      } catch (SQLException e) {
      }
  }

  @Override
  public String getUnit() {
    return "records";
  }

  @Override
  public long getProgress() {
    return progress;
  }

  @Override
  public long getTotal() {
    return total;
  }

  @Override
  public boolean hasNext() {
    try {
      if (!didNext) {
        hasNext = rs.next();
        progress++;
        didNext = true;
      }
      return hasNext;
    } catch (SQLException e) {
      throw new OExtractorException(getName() + ": error on moving forward in resultset of query '" + query
          + "'. Previous position was " + progress, e);
    }
  }

  @Override
  public Object next() {
    try {
      if (!didNext) {
        if (!rs.next())
          throw new NoSuchElementException("Previous position was " + progress);
        progress++;
      }
      didNext = false;

      final ODocument doc = new ODocument();
      for (int i = 0; i < rsColumns; i++) {
        // final OType fieldType = columnTypes != null ? columnTypes.get(i) : null;
        Object fieldValue = rs.getObject(i + 1);
        doc.field(columnNames.get(i), fieldValue);
      }
      return doc;

    } catch (SQLException e) {
      throw new OExtractorException(getName() + ": error on moving forward in resultset of query '" + query
          + "'. Previous position was " + progress, e);
    }
  }

  @Override
  public ODocument getConfiguration() {
    return new ODocument().fromJSON("{parameters:[{driver:{optional:false,description:'JDBC Driver class'}},"
        + "{url:{optional:false,description:'Connection URL'}}," + "{userName:{optional:false,description:'User name'}},"
        + "{userPassword:{optional:false,description:'User password'}},"
        + "{query:{optional:false,description:'Query that extract records'}},"
        + "{queryCount:{optional:true,description:'Query that returns the count to have a correct progress status'}}],"
        + "output:'ODocument'}");
  }

  @Override
  public String getName() {
    return "jdbc";
  }
}