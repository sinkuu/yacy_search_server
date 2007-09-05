//psParser.java 
//------------------------
//part of YaCy
//(C) by Michael Peter Christen; mc@anomic.de
//first published on http://www.anomic.de
//Frankfurt, Germany, 2007
//
//this file is contributed by Martin Thelian
//last major change: 15.09.2005
//
//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; either version 2 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program; if not, write to the Free Software
//Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//Using this software in any meaning (reading, learning, copying, compiling,
//running) means that you agree that the Author(s) is (are) not responsible
//for cost, loss of data or any harm that may be caused directly or indirectly
//by usage of this softare or this documentation. The usage of this software
//is on your own risk. The installation and usage (starting/running) of this
//software may allow other people or application to access your computer and
//any attached devices and is highly dependent on the configuration of the
//software which must be done by the user of the software; the author(s) is
//(are) also not responsible for proper configuration and usage of the
//software, even if provoked by documentation provided together with
//the software.
//
//Any changes to this file according to the GPL as documented in the file
//gpl.txt aside this file in the shipment you received can be done to the
//lines that follows this copyright notice here, but changes must not be
//done inside the copyright notive above. A re-distribution must contain
//the intact and unchanged copyright notice.
//Contributions and changes to the program code must be marked as such.

package de.anomic.plasma.parser.ps;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Hashtable;

import de.anomic.plasma.plasmaParserDocument;
import de.anomic.plasma.parser.AbstractParser;
import de.anomic.plasma.parser.Parser;
import de.anomic.plasma.parser.ParserException;
import de.anomic.server.serverFileUtils;
import de.anomic.yacy.yacyURL;

public class psParser extends AbstractParser implements Parser {

    /**
     * a list of mime types that are supported by this parser class
     * @see #getSupportedMimeTypes()
     */
    public static final Hashtable SUPPORTED_MIME_TYPES = new Hashtable();    
    static { 
        SUPPORTED_MIME_TYPES.put("application/postscript","ps"); 
        SUPPORTED_MIME_TYPES.put("text/postscript","ps");
    }     
    
    /**
     * a list of library names that are needed by this parser
     * @see Parser#getLibxDependences()
     */
    private static final String[] LIBX_DEPENDENCIES = new String[] {};          
    
    private static Object modeScan = new Object();
    private static boolean modeScanDone = false;
    private static String parserMode = "java";
    
    public psParser() {        
        super(LIBX_DEPENDENCIES);
        this.parserName = "PostScript Document Parser"; 
        if (!modeScanDone) synchronized (modeScan) {
        	if (testForPs2Ascii()) parserMode = "ps2ascii";
        	else parserMode = "java";
        	modeScanDone = true;
		}
    }
    
    public Hashtable getSupportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }
    
    public boolean testForPs2Ascii() {
        try {
            String procOutputLine = null;
            StringBuffer procOutput = new StringBuffer();
            
            Process ps2asciiProc = Runtime.getRuntime().exec(new String[]{"ps2ascii", "--version"});
            BufferedReader stdOut = new BufferedReader(new InputStreamReader(ps2asciiProc.getInputStream()));
            while ((procOutputLine = stdOut.readLine()) != null) {
                procOutput.append(procOutputLine).append(", ");
            }
            int returnCode = ps2asciiProc.waitFor();
            return (returnCode == 0);
        } catch (Exception e) {
            if (this.theLogger != null) this.theLogger.logInfo("ps2ascii not found. Switching to java parser mode.");
            return false;
        }
    }
    
    
    public plasmaParserDocument parse(yacyURL location, String mimeType, String charset, File sourceFile) throws ParserException, InterruptedException {
        
    	File outputFile = null;
        try { 
        	// creating a temp file for the output
        	outputFile = super.createTempFile("ascii.txt");
        	
        	// decide with parser mode to use
            if (parserMode.equals("ps2ascii")) {
                parseUsingPS2ascii(sourceFile,outputFile);
            } else {
                parseUsingJava(sourceFile,outputFile);
            }
            
            // return result
            plasmaParserDocument theDoc = new plasmaParserDocument(
                    location,
                    mimeType,
                    "UTF-8",
                    null,
                    null,
                    "",
                    null,
                    null,
                    outputFile,
                    null,
                    null);         
            
            return theDoc;
        } catch (Exception e) {            
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;
            
            // delete temp file
            if (outputFile != null) outputFile.delete();
            
            // throw exception
            throw new ParserException("Unexpected error while parsing ps file. " + e.getMessage(),location); 
        } 
    }    
    
    public void parseUsingJava (File inputFile, File outputFile) throws Exception {
        
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            reader = new BufferedReader(new FileReader(inputFile));
            writer = new BufferedWriter(new FileWriter(outputFile));
            
            String versionInfoLine = reader.readLine();
            String version = versionInfoLine.substring(versionInfoLine.length()-3);

            int ichar = 0;
            boolean isComment = false;
            boolean isText = false;
            
            if (version.startsWith("2")) {
                boolean isConnector = false;
                
                while ((ichar = reader.read()) > 0) {
                    if (isConnector) {
                        if (ichar < 108) {
                            writer.write(' ');
                        }
                        isConnector = false;
                    } else if (ichar == '%') {
                        isComment = true; 
                    } else if (ichar == '\n' && isComment) {
                        isComment = false;
                    } else if (ichar == ')' && isText ) {
                        isConnector = true;
                        isText = false;
                    } else if (isText) {
                    	writer.write((char)ichar);
                    } else if (ichar == '(' && !isComment) {
                        isText = true;
                    }
                }
              
            } else  if (version.startsWith("3")) {
                StringBuffer stmt = new StringBuffer();
                boolean isBMP = false;
                boolean isStore = false;
                int store = 0;
                
                while ((ichar = reader.read()) > 0) {
                    if (ichar == '%') {
                        isComment = true;
                    } else if (ichar == '\n' && isComment){
                        isComment = false;
                    } else if (ichar == ')' && isText ) {
                        isText = false;
                    } else if (isText && !isBMP) {
                    	writer.write((char)ichar);
                    } else if (ichar == '(' && !isComment && !isBMP) {
                        isText = true;
                    } else if (isStore) {
                        if (store == 9 || ichar == ' ' || ichar == 10) {
                            isStore = false;                    
                            store = 0;
                            if (stmt.toString().equals("BEGINBITM")) {
                                isText = false;
                                isBMP = true;
                            } else if (stmt.toString().equals("ENDBITMAP")) {
                                isBMP = false;
                            }
                            stmt.delete(0,stmt.length());
                        }
                        else {
                            stmt.append((char)ichar);
                            store++;
                        }
                    } else if (!isComment && !isStore && (ichar == 66 || ichar == 69)) {    
                        isStore = true;
                        stmt.append((char)ichar);
                        store++;
                    }  
                }                
            } else {
                throw new Exception("Unsupported Postscript version '" + version + "'.");
            }            
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception e) {/* */}
            if (writer != null) try { writer.close(); } catch (Exception e) {/* */}
        }
              

    }
    
    /**
     * This function requires the ghostscript-library
     * @param inputFile
     * @param outputFile
     * @throws Exception
     */
    private void parseUsingPS2ascii(File inputFile, File outputFile) throws Exception {
    	int execCode = 0;
    	StringBuffer procErr = null;
    	try {
    		String procOutputLine = null;
    		StringBuffer procOut = new StringBuffer();
    		procErr = new StringBuffer();
    		
    		Process ps2asciiProc = Runtime.getRuntime().exec(new String[]{"ps2ascii", inputFile.getAbsolutePath(),outputFile.getAbsolutePath()});
    		BufferedReader stdOut = new BufferedReader(new InputStreamReader(ps2asciiProc.getInputStream()));
    		BufferedReader stdErr = new BufferedReader(new InputStreamReader(ps2asciiProc.getErrorStream()));
    		while ((procOutputLine = stdOut.readLine()) != null) {
    			procOut.append(procOutputLine);
    		}
    		while ((procOutputLine = stdErr.readLine()) != null) {
    			procErr.append(procOutputLine);
    		}
    		execCode = ps2asciiProc.waitFor();
    	} catch (Exception e) {
    		String errorMsg = "Unable to convert ps to ascii. " + e.getMessage();
    		this.theLogger.logSevere(errorMsg);
    		throw new Exception(errorMsg);
    	}
    	
    	if (execCode != 0) throw new Exception("Unable to convert ps to ascii. ps2ascii returned statuscode " + execCode + "\n" + procErr.toString());
    }
    
    public void reset() {
		// Nothing todo here at the moment
    	super.reset();
    }

    public plasmaParserDocument parse(yacyURL location, String mimeType, String charset, InputStream source) throws ParserException, InterruptedException {
        
        File tempFile = null;
        try {
            // creating a tempfile
            tempFile = super.createTempFile("temp.ps");
            tempFile.deleteOnExit();
            
            // copying inputstream into file
            serverFileUtils.copy(source,tempFile);
            
            // parsing the file
            return parse(location,mimeType,charset,tempFile);
        } catch (Exception e) {
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;        	
        	
            throw new ParserException("Unable to parse the ps file. " + e.getMessage(),location, e);
        } finally {
            if (tempFile != null) try{ tempFile.delete(); }catch(Exception e) {/* */}
        }
    }

}
