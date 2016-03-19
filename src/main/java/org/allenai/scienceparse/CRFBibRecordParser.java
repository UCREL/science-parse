package org.allenai.scienceparse;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.allenai.ml.linalg.DenseVector;
import org.allenai.ml.linalg.Vector;
import org.allenai.ml.sequences.StateSpace;
import org.allenai.ml.sequences.crf.CRFFeatureEncoder;
import org.allenai.ml.sequences.crf.CRFModel;
import org.allenai.ml.sequences.crf.CRFWeightsEncoder;
import org.allenai.ml.util.IOUtils;
import org.allenai.ml.util.Indexer;
import org.allenai.scienceparse.ExtractReferences.BibRecordParser;
import org.allenai.scienceparse.ExtractReferences.NamedYear;
import org.allenai.scienceparse.ExtractedMetadata.LabelSpan;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import com.sun.media.jfxmedia.logging.Logger;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CRFBibRecordParser implements BibRecordParser {
  private static CRFModel<String, String, String> model;
  public static final String DATA_VERSION = "0.1";
  
  public CRFBibRecordParser(CRFModel<String, String, String> inModel) {
    model = inModel;
  }

  public static List<Pair<String, String>> getLabeledLine(String s) {
    final String [] sourceTags = new String [] {"author", "booktitle", "date", "editor", "institution",
        "journal", "location", "note", "pages", "publisher", "tech", "title", "volume"}; //MUST BE SORTED
    boolean [] inTag = new boolean [] {false, false, false, false, false, 
        false, false, false,false, false,
        false, false, false};
    final String [] destTags = new String [] {ExtractedMetadata.authorTag, 
        ExtractedMetadata.venueTag, ExtractedMetadata.yearTag, "editor", "institution", 
        ExtractedMetadata.venueTag, "location", "note", "pages", "publisher", ExtractedMetadata.venueTag,
        ExtractedMetadata.titleTag, "volume"};
    
    final Pattern yearPat= Pattern.compile("[1-2][0-9]{3}.*");
    List<String> toks = tokenize(s);
    List<Pair<String, String>> out = new ArrayList<>();
    out.add(Tuples.pair("<S>", "<S>"));
    boolean atStart = false;
    for(int i=0; i<toks.size(); i++) {
      String sTok = toks.get(i);
      if(sTok.endsWith(">")) {
        String t = sTok.replaceAll("<", "").replaceAll("/", "").replaceAll(">", "");
        int idx = Arrays.binarySearch(sourceTags, t);
        if(idx >= 0) { //ignore unescaped XML chars in bib
           inTag[idx] = (sTok.startsWith("</"))?false:true;
           atStart = inTag[idx];
        }
      }
      else { //write it out with proper tag
        String tag = "O";
        for(int j=0; j<inTag.length; j++) {
          if(inTag[j]) {
            tag = destTags[j];
            break;
          }
        }
        if(i<toks.size()-1) { //note we can only be inside a tag if this condition holds
          if(toks.get(i+1).startsWith("</")) { //our tag ends on next word(assuming no nesting)
            if(atStart) { //single-word span
              tag = "W_" + tag;
            }
            else {
              tag = "E_" + tag;
            }
          }
          else if(atStart)
            tag = "B_" + tag;
          else
            tag = "I_" + tag;
        }
        out.add(Tuples.pair(sTok, tag));
        atStart = false;
      }
    }
    
    out.add(Tuples.pair("</S>", "</S>"));
    return out;
  }
  
  public static List<List<Pair<String, String>>> labelFromCoraFile(File trainFile) throws IOException {
    List<List<Pair<String, String>>> out = new ArrayList<>();
    BufferedReader brIn = new BufferedReader(new InputStreamReader(new FileInputStream(trainFile), "UTF-8"));
    String sLine;
    while((sLine = brIn.readLine())!=null) {
      List<Pair<String,String>> labeledLine = getLabeledLine(sLine);
      out.add(labeledLine);
    }
    brIn.close();
    return out;
  }
  
  private static List<String> tokenize(String line) { //tokenizes string for use in bib parsing
    //line = line.replaceAll("([a-z0-9])(,|\\.)", "$1 $2"); //break on lowercase alpha or num before . or ,
    String [] toks = line.split(" "); //otherwise not fancy
    return Arrays.asList(toks);
  }
  
  public BibRecord parseRecord(String line) {
    line = line.trim();
    if(line.isEmpty())
      return null;
    Matcher m = RegexWithTimeout.matcher(ExtractReferences.pBracket,  line);
    String citeRegEx = null;
    String shortCiteRegEx = null;
    if(m.matches()) {
      citeRegEx = m.group(1);
      shortCiteRegEx = citeRegEx;
      line = m.group(2);
    } else {
      m = RegexWithTimeout.matcher(ExtractReferences.pDot, line);
      if(m.matches()) {
        citeRegEx = m.group(1);
        shortCiteRegEx = citeRegEx;
        line = m.group(2);
      }
    }
    line = line.trim();
    if(line.isEmpty())
      return null;
//    log.info("parsing " + line);
    ArrayList<String> toks = new ArrayList<String>();
    toks.add("<S>");
    toks.addAll(tokenize(line));
    toks.add("</S>");
    List<String> labels;
    try{
      labels = model.bestGuess(toks);
    } catch(final Exception e) {
      return null;
    }
    labels = PDFToCRFInput.padTagSequence(labels);
    List<LabelSpan> lss = ExtractedMetadata.getSpans(labels);
    
    String title = null;
    String author = null;
    String venue = null;
    String year = null;
    for (LabelSpan ls : lss) {
      //TODO: figure out how to make this code less redundant
      if (title == null && ls.tag.equals(ExtractedMetadata.titleTag)) { //only take the first
        title = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      } else if (author == null && ls.tag.equals(ExtractedMetadata.authorTag)) {
        author = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      } else if (venue == null && ls.tag.equals(ExtractedMetadata.venueTag)) {
        venue = PDFToCRFInput.stringAtForStringList(toks, ls.loc); 
      } else if (year == null && ls.tag.equals(ExtractedMetadata.yearTag)) {
        year = PDFToCRFInput.stringAtForStringList(toks, ls.loc);
      }
    }
    
    List<String> authors =
            author == null ?
                    null :
                    ExtractReferences.authorStringToList(author);
//    log.info("authors first extracted: " + ((authors==null)?"":authors.toString()));
//    log.info("year first extracted: " + year);
    
    int iYear = -1;
    if(year != null)
     iYear = ExtractReferences.extractRefYear(year);
    
    if(citeRegEx == null && year != null) {
      shortCiteRegEx = ExtractReferences.getCiteAuthorFromAuthors(authors);
      citeRegEx = shortCiteRegEx + ",? " + ((iYear > 0)?Pattern.quote(iYear + ""):"");
    }
    if(citeRegEx == null || shortCiteRegEx == null || title == null || authors == null || year == null)
      return null;
//    log.info("cite string " + citeRegEx);
    BibRecord brOut = null;
    try {
      brOut = new BibRecord(
        Parser.cleanTitle(title),
        Parser.trimAuthors(authors),
        Parser.cleanTitle(venue),
        Pattern.compile(citeRegEx),
        Pattern.compile(shortCiteRegEx),
        iYear);
    } catch (final NumberFormatException e) {
      return null;
    }
//    log.info("returning " + brOut.toString());
    return brOut;
  }
}