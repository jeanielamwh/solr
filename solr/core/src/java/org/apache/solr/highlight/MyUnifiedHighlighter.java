package org.apache.solr.highlight;

import java.io.IOException;
import java.io.StringReader;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.common.params.HighlightParams;

public class MyUnifiedHighlighter extends UnifiedSolrHighlighter {

    @Override
    protected UnifiedHighlighter getHighlighter(SolrQueryRequest req) {
        return new MySolrExtendedUnifiedHighlighter(req);
    }

    protected static class MySolrExtendedUnifiedHighlighter extends SolrExtendedUnifiedHighlighter {

        public MySolrExtendedUnifiedHighlighter(SolrQueryRequest req) {
            super(req);
        }

        private String stripHTML(String value) {
            StringBuilder out = new StringBuilder();
            StringReader strReader = new StringReader(value);
            try {
                HTMLStripCharFilter html = new HTMLStripCharFilter(strReader.markSupported() ? strReader : new BufferedReader(strReader));
                char[] cbuf = new char[1024 * 10];
                while (true) {
                    int count = html.read(cbuf);
                    if (count == -1)
                        break; // end of stream mark is -1
                    if (count > 0)
                        out.append(cbuf, 0, count);
                }
                html.close();
            } catch (IOException e) {
                // ...
            }
            return out.toString();
        }

        @Override
        protected PassageFormatter getFormatter(String fieldName) {
            String preTag =
                params.getFieldParam(
                    fieldName,
                    HighlightParams.TAG_PRE,
                    params.getFieldParam(fieldName, HighlightParams.SIMPLE_PRE, "<em>"));
            String postTag =
                params.getFieldParam(
                    fieldName,
                    HighlightParams.TAG_POST,
                    params.getFieldParam(fieldName, HighlightParams.SIMPLE_POST, "</em>"));
            String ellipsis =
                params.getFieldParam(fieldName, HighlightParams.TAG_ELLIPSIS, SNIPPET_SEPARATOR);
            String encoder = params.getFieldParam(fieldName, HighlightParams.ENCODER, "simple");

            String tmpPreTag = "!!foo!!";
            String tmpPostTag = "!!bar!!";

            DefaultPassageFormatter formatter = new DefaultPassageFormatter(tmpPreTag, tmpPostTag, ellipsis, "html".equals(encoder));

            return new PassageFormatter() {
                @Override
                public String format(Passage[] passages, String content) {
                    String preStrip = formatter.format(passages, content);
                    String postStrip = stripHTML(preStrip);
                    // TODO replace tmp tags with real ones
                    return postStrip;
                }
            };
        }
    }
}

