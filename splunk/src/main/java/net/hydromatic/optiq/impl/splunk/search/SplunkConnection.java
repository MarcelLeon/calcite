/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.impl.splunk.search;

import net.hydromatic.linq4j.Enumerator;
import net.hydromatic.linq4j.Linq4j;
import net.hydromatic.optiq.impl.splunk.util.HttpUtils;
import net.hydromatic.optiq.impl.splunk.util.StringUtils;

import au.com.bytecode.opencsv.CSVReader;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.hydromatic.optiq.impl.splunk.util.HttpUtils.*;

/**
 * Connection to Splunk.
 */
public class SplunkConnection {
  private static final Logger LOGGER =
      Logger.getLogger(SplunkConnection.class.getName());

  private static final Pattern SESSION_KEY =
      Pattern.compile(
          "<response>\\s*<sessionKey>([0-9a-f]+)</sessionKey>\\s*</response>");

  final URL url;
  final String username, password;
  String sessionKey;
  final Map<String, String> requestHeaders = new HashMap<String, String>();

  public SplunkConnection(String url, String username, String password)
    throws MalformedURLException {
    this(new URL(url), username, password);
  }

  public SplunkConnection(URL url, String username, String password) {
    this.url      = url;
    this.username = username;
    this.password = password;
    connect();
  }

  private static void close(Closeable c) {
    try {
      c.close();
    } catch (Exception ignore) {
      // ignore
    }
  }

  private void connect() {
    BufferedReader rd = null;

    try {
      String loginUrl =
          String.format(
              "%s://%s:%d/services/auth/login",
              url.getProtocol(),
              url.getHost(),
              url.getPort());

      StringBuilder data = new StringBuilder();
      appendURLEncodedArgs(
          data, "username", username, "password", password);

      rd = new BufferedReader(
          new InputStreamReader(
              post(
                  loginUrl,
                  data,
                  requestHeaders)));

      String line;
      StringBuilder reply = new StringBuilder();
      while ((line = rd.readLine()) != null) {
        reply.append(line);
        reply.append("\n");
      }

      Matcher m = SESSION_KEY.matcher(reply);
      if (m.find()) {
        sessionKey = m.group(1);
        requestHeaders.put("Authorization", "Splunk " + sessionKey);
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      close(rd);
    }
  }

  public void getSearchResults(
      String search,
      Map<String, String> otherArgs,
      List<String> fieldList,
      SearchResultListener srl) {
    assert srl != null;
    Enumerator x = getSearchResults_(search, otherArgs, fieldList, srl);
    assert x == null;
  }

  public Enumerator getSearchResultIterator(
      String search,
      Map<String, String> otherArgs,
      List<String> fieldList) {
    return getSearchResults_(search, otherArgs, fieldList, null);
  }

  private Enumerator getSearchResults_(
      String search,
      Map<String, String> otherArgs,
      List<String> wantedFields,
      SearchResultListener srl) {
    String searchUrl =
        String.format(
            "%s://%s:%d/services/search/jobs/export",
            url.getProtocol(),
            url.getHost(),
            url.getPort());

    StringBuilder data = new StringBuilder();
    Map<String, String> args = new LinkedHashMap<String, String>();
    if (otherArgs != null) {
      args.putAll(otherArgs);
    }
    args.put("search", search);
    // override these args
    args.put("output_mode", "csv");
    args.put("preview", "0");

    // TODO: remove this once the csv parser can handle leading spaces
    args.put("check_connection", "0");

    appendURLEncodedArgs(data, args);
    try {
      // wait at most 30 minutes for first result
      InputStream in =
          post(searchUrl, data, requestHeaders, 10000, 1800000);
      if (srl == null) {
        return new SplunkResultIterator(in, wantedFields);
      } else {
        parseResults(
            in,
            srl);
        return null;
      }
    } catch (Exception e) {
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      LOGGER.warning(e.getMessage() + "\n"
          + sw);
      return srl == null ? Linq4j.emptyEnumerator() : null;
    }
  }

  private static void parseResults(InputStream in, SearchResultListener srl)
    throws IOException {
    CSVReader csvr = new CSVReader(new InputStreamReader(in));
    try {
      String [] header = csvr.readNext();

      if (header != null
          && header.length > 0
          && !(header.length == 1 && header[0].isEmpty())) {
        srl.setFieldNames(header);

        String[] line;
        while ((line = csvr.readNext()) != null) {
          if (line.length == header.length) {
            srl.processSearchResult(line);
          }
        }
      }
    } catch (IOException ignore) {
      StringWriter sw = new StringWriter();
      ignore.printStackTrace(new PrintWriter(sw));
      LOGGER.warning(ignore.getMessage() + "\n"
          + sw);
    } finally {
      HttpUtils.close(csvr); // CSVReader closes the inputstream too
    }
  }

  static class DummySearchResultListener implements SearchResultListener {
    String[] fieldNames = null;
    int resultCount = 0;
    boolean print = false;

    public DummySearchResultListener(boolean print) {
      this.print = print;
    }

    public void setFieldNames(String[] fieldNames) {
      this.fieldNames = fieldNames;
    }

    public boolean processSearchResult(String[] values) {
      resultCount++;
      if (print) {
        for (int i = 0; i < this.fieldNames.length; ++i) {
          System.out.printf("%s=%s\n", this.fieldNames[i], values[i]);
        }
        System.out.println();
      }
      return true;
    }

    public int getResultCount() {
      return resultCount;
    }
  }

  public static void parseArgs(String[] args, Map<String, String> map) {
    for (int i = 0; i < args.length; i++) {
      String argName = args[i++];
      String argValue = i < args.length ? args[i] : "";

      if (!argName.startsWith("-")) {
        throw new IllegalArgumentException(
            "invalid argument name: " + argName
            + ". Argument names must start with -");
      }
      map.put(argName.substring(1), argValue);
    }
  }

  public static void printUsage(String errorMsg) {
    String[] strings = {
      "Usage: java Connection -<arg-name> <arg-value>",
      "The following <arg-name> are valid",
      "search        - required, search string to execute",
      "field_list    - "
        + "required, list of fields to request, comma delimited",
      "uri           - "
        + "uri to splunk's mgmt port, default: https://localhost:8089",
      "username      - "
        + "username to use for authentication, default: admin",
      "password      - "
        + "password to use for authentication, default: changeme",
      "earliest_time - earliest time for the search, default: -24h",
      "latest_time   - latest time for the search, default: now",
      "-print        - whether to print results or just the summary"
    };
    System.err.println(errorMsg);
    for (String s : strings) {
      System.err.println(s);
    }
    System.exit(1);
  }

  public static void main(String[] args) throws MalformedURLException {
    Map<String, String> argsMap = new HashMap<String, String>();
    argsMap.put("uri",           "https://localhost:8089");
    argsMap.put("username",      "admin");
    argsMap.put("password",      "changeme");
    argsMap.put("earliest_time", "-24h");
    argsMap.put("latest_time",   "now");
    argsMap.put("-print",        "true");

    parseArgs(args, argsMap);


    String search = argsMap.get("search"),
        field_list = argsMap.get("field_list");

    if (search == null) {
      printUsage("Missing required argument: search");
    }
    if (field_list == null) {
      printUsage("Missing required argument: field_list");
    }

    List<String> fieldList = StringUtils.decodeList(field_list, ',');

    SplunkConnection c =
        new SplunkConnection(
            argsMap.get("uri"),
            argsMap.get("username"),
            argsMap.get("password"));

    Map<String, String> searchArgs = new HashMap<String, String>();
    searchArgs.put("earliest_time", argsMap.get("earliest_time"));
    searchArgs.put("latest_time", argsMap.get("latest_time"));
    searchArgs.put(
        "field_list",
        StringUtils.encodeList(fieldList, ',').toString());


    DummySearchResultListener dummy =
        new DummySearchResultListener(
            Boolean.valueOf(argsMap.get("-print")));
    long start = System.currentTimeMillis();
    c.getSearchResults(search, searchArgs, null, dummy);

    System.out.printf(
        "received %d results in %dms\n",
        dummy.getResultCount(),
        System.currentTimeMillis() - start);
  }

  private static class SplunkResultIterator implements Enumerator {
    private final CSVReader csvReader;
    private String[] fieldNames;
    private int[] sources;
    private Object current;

    /**
     * Where to find the singleton field, or whether to map. Values:
     *
     * <ul>
     * <li>Non-negative The index of the sole field</li>
     * <li>-1 Generate a singleton null field for every record</li>
     * <li>-2 Return line intact</li>
     * <li>-3 Use sources to re-map</li>
     * </ul>
     */
    private int source;

    public SplunkResultIterator(InputStream in, List<String> wantedFields) {
      csvReader = new CSVReader(new InputStreamReader(in));
      try {
        fieldNames = csvReader.readNext();
        if (fieldNames == null
            || fieldNames.length == 0
            || fieldNames.length == 1 && fieldNames[0].isEmpty()) {
        } else {
          final List<String> headerList = Arrays.asList(fieldNames);
          if (wantedFields.size() == 1) {
            // Yields 0 or higher if wanted field exists.
            // Yields -1 if wanted field does not exist.
            source = headerList.indexOf(wantedFields.get(0));
            assert source >= -1;
            sources = null;
          } else if (wantedFields.equals(headerList)) {
            source = -2;
          } else {
            source = -3;
            sources = new int[wantedFields.size()];
            int i = 0;
            for (String wantedField : wantedFields) {
              sources[i++] = headerList.indexOf(wantedField);
            }
          }
        }
      } catch (IOException ignore) {
        StringWriter sw = new StringWriter();
        ignore.printStackTrace(new PrintWriter(sw));
        LOGGER.warning(ignore.getMessage() + "\n"
            + sw);
      } finally {
      }
    }

    public Object current() {
      return current;
    }

    public boolean moveNext() {
      try {
        String[] line;
        while ((line = csvReader.readNext()) != null) {
          if (line.length == fieldNames.length) {
            switch (source) {
            case -3:
              // Re-map using sources
              String[] mapped = new String[sources.length];
              for (int i = 0; i < sources.length; i++) {
                int source1 = sources[i];
                mapped[i] = source1 < 0 ? null : line[source1];
              }
              this.current = mapped;
              break;
            case -2:
              // Return line as is. No need to re-map.
              current = line;
              break;
            case -1:
              // Singleton null
              this.current = null;
              break;
            default:
              this.current = line[source];
              break;
            }
            return true;
          }
        }
      } catch (IOException ignore) {
        StringWriter sw = new StringWriter();
        ignore.printStackTrace(new PrintWriter(sw));
        LOGGER.warning(ignore.getMessage() + "\n"
            + sw);
      }
      return false;
    }

    public void reset() {
      throw new UnsupportedOperationException();
    }

    public void close() {
      try {
        csvReader.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}

// End SplunkConnection.java
