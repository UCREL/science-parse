package org.allenai.scienceparse;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;
import org.allenai.scienceparse.pdfapi.PDFToken;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;
//TODO: upgrade to pdfbox 2.0 when released

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.tuple.Tuples;

@Slf4j
public class PDFToCRFInput {
		
	public PDFToCRFInput() throws IOException {
		super();
	}	
	
	/**
	 * Returns the index of start (inclusive) and end (exclusive)
	 * of first occurrence of string in seq, or null if not found
	 * @param seq	String to find, assumes tokens are space-delimited 
	 * @return
	 */
	public static Pair<Integer, Integer> findString(List<PaperToken> seq, String toFind) {
		String [] toks = toFind.split(" ");
		int nextToMatch = 0;
		int idx = 0;
		for(PaperToken pt : seq) {
			if(toks[nextToMatch].equals(pt.getPdfToken().token)) {
				nextToMatch++;
			}
			else {
				nextToMatch = 0;
			}
			idx++;
			if(nextToMatch==toks.length)
				return Tuples.pair(idx-toks.length, idx);
		}
		return null;
	}
	
	/**
	 * Returns the PaperToken sequence form of a given PDF document<br>
	 * @param pdd	The PDF Document to convert into instances  
	 * @return	The data sequence
	 * @throws IOException 
	 */
	public static List<PaperToken> getSequence(PDFDoc pdf) throws IOException {
		
		ArrayList<PaperToken> out = new ArrayList<>();
		int pg = 0;
		for(PDFPage p : pdf.getPages()) {
			int ln = 0;
			for(PDFLine l : p.getLines()) {
				l.tokens.forEach((PDFToken t) -> out.add(
						new PaperToken(t, ln, pg))
						);
			}
		}
		return out;
	}
}
