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
package org.eigenbase.util;

import java.io.*;

import java.util.*;

/**
 * A helper class for generating formatted text. StackWriter keeps track of
 * nested formatting state like indentation level and quote escaping. Typically,
 * it is inserted between a PrintWriter and the real Writer; directives are
 * passed straight through the PrintWriter via the write method, as in the
 * following example:
 *
 * <pre><code>
 *    StringWriter sw = new StringWriter();
 *    StackWriter stackw = new StackWriter(sw, StackWriter.INDENT_SPACE4);
 *    PrintWriter pw = new PrintWriter(stackw);
 *    pw.write(StackWriter.INDENT);
 *    pw.print("execute remote(link_name,");
 *    pw.write(StackWriter.OPEN_SQL_STRING_LITERAL);
 *    pw.println();
 *    pw.write(StackWriter.INDENT);
 *    pw.println("select * from t where c > 'alabama'");
 *    pw.write(StackWriter.OUTDENT);
 *    pw.write(StackWriter.CLOSE_SQL_STRING_LITERAL);
 *    pw.println(");");
 *    pw.write(StackWriter.OUTDENT);
 *    pw.close();
 *    System.out.println(sw.toString());
 * </code></pre>
 *
 * which produces the following output:
 *
 * <pre><code>
 *      execute remote(link_name,'
 *          select * from t where c > ''alabama''
 *      ');
 * </code></pre>
 */
public class StackWriter extends FilterWriter {
  //~ Static fields/initializers ---------------------------------------------

  /**
   * directive for increasing the indentation level
   */
  public static final int INDENT = 0xF0000001;

  /**
   * directive for decreasing the indentation level
   */
  public static final int OUTDENT = 0xF0000002;

  /**
   * directive for beginning an SQL string literal
   */
  public static final int OPEN_SQL_STRING_LITERAL = 0xF0000003;

  /**
   * directive for ending an SQL string literal
   */
  public static final int CLOSE_SQL_STRING_LITERAL = 0xF0000004;

  /**
   * directive for beginning an SQL identifier
   */
  public static final int OPEN_SQL_IDENTIFIER = 0xF0000005;

  /**
   * directive for ending an SQL identifier
   */
  public static final int CLOSE_SQL_IDENTIFIER = 0xF0000006;

  /**
   * tab indentation
   */
  public static final String INDENT_TAB = "\t";

  /**
   * four-space indentation
   */
  public static final String INDENT_SPACE4 = "    ";
  private static final Character singleQuote = new Character('\'');
  private static final Character doubleQuote = new Character('"');

  //~ Instance fields --------------------------------------------------------

  private int indentationDepth;
  private String indentation;
  private boolean needIndent;
  private LinkedList quoteStack;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new StackWriter on top of an existing Writer, with the
   * specified string to be used for each level of indentation.
   *
   * @param writer      underyling writer
   * @param indentation indentation unit such as {@link #INDENT_TAB} or {@link
   *                    #INDENT_SPACE4}
   */
  public StackWriter(Writer writer, String indentation) {
    super(writer);
    this.indentation = indentation;
    quoteStack = new LinkedList();
  }

  //~ Methods ----------------------------------------------------------------

  private void indentIfNeeded() throws IOException {
    if (needIndent) {
      for (int i = 0; i < indentationDepth; i++) {
        out.write(indentation);
      }
      needIndent = false;
    }
  }

  private void writeQuote(Character quoteChar) throws IOException {
    indentIfNeeded();
    int n = 1;
    for (int i = 0; i < quoteStack.size(); i++) {
      if (quoteStack.get(i).equals(quoteChar)) {
        n *= 2;
      }
    }
    for (int i = 0; i < n; i++) {
      out.write(quoteChar.charValue());
    }
  }

  private void pushQuote(Character quoteChar) throws IOException {
    writeQuote(quoteChar);
    quoteStack.addLast(quoteChar);
  }

  private void popQuote(Character quoteChar) throws IOException {
    if (!(quoteStack.removeLast().equals(quoteChar))) {
      throw new Error("mismatched quotes");
    }
    writeQuote(quoteChar);
  }

  // implement Writer
  public void write(int c) throws IOException {
    switch (c) {
    case INDENT:
      indentationDepth++;
      break;
    case OUTDENT:
      indentationDepth--;
      break;
    case OPEN_SQL_STRING_LITERAL:
      pushQuote(singleQuote);
      break;
    case CLOSE_SQL_STRING_LITERAL:
      popQuote(singleQuote);
      break;
    case OPEN_SQL_IDENTIFIER:
      pushQuote(doubleQuote);
      break;
    case CLOSE_SQL_IDENTIFIER:
      popQuote(doubleQuote);
      break;
    case '\n':
      out.write(c);
      needIndent = true;
      break;
    case '\r':

      // NOTE jvs 3-Jan-2006:  suppress indentIfNeeded() in this case
      // so that we don't get spurious diffs on Windows vs. Linux
      out.write(c);
      break;
    case '\'':
      writeQuote(singleQuote);
      break;
    case '"':
      writeQuote(doubleQuote);
      break;
    default:
      indentIfNeeded();
      out.write(c);
      break;
    }
  }

  // implement Writer
  public void write(char[] cbuf, int off, int len) throws IOException {
    // TODO: something more efficient using searches for
    // special characters
    for (int i = off; i < (off + len); i++) {
      write(cbuf[i]);
    }
  }

  // implement Writer
  public void write(String str, int off, int len) throws IOException {
    // TODO: something more efficient using searches for
    // special characters
    for (int i = off; i < (off + len); i++) {
      write(str.charAt(i));
    }
  }

  /**
   * Writes an SQL string literal.
   *
   * @param pw PrintWriter on which to write
   * @param s  text of literal
   */
  public static void printSqlStringLiteral(PrintWriter pw, String s) {
    pw.write(OPEN_SQL_STRING_LITERAL);
    pw.print(s);
    pw.write(CLOSE_SQL_STRING_LITERAL);
  }

  /**
   * Writes an SQL identifier.
   *
   * @param pw PrintWriter on which to write
   * @param s  identifier
   */
  public static void printSqlIdentifier(PrintWriter pw, String s) {
    pw.write(OPEN_SQL_IDENTIFIER);
    pw.print(s);
    pw.write(CLOSE_SQL_IDENTIFIER);
  }
}

// End StackWriter.java
