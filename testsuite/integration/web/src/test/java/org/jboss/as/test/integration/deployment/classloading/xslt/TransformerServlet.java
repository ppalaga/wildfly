package org.jboss.as.test.integration.deployment.classloading.xslt;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.jboss.as.test.shared.FileUtils;
import org.jboss.logging.Logger;
import org.junit.Assert;

@WebServlet("/transformer")
public class TransformerServlet extends HttpServlet {
    private static Logger log = Logger.getLogger(TransformerServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, final HttpServletResponse resp) throws IOException{
        final String ctx = req.getContextPath();
        final ErrorListener errorListener = new ErrorListener() {
            @Override
            public void warning(TransformerException e) throws TransformerException {
                log.warnf(e, "in context %s", ctx);
            }

            @Override
            public void fatalError(TransformerException e) throws TransformerException {
                try {
                    e.printStackTrace(resp.getWriter());
                } catch (IOException e1) {
                }
            }

            @Override
            public void error(TransformerException e) throws TransformerException {
                try {
                    e.printStackTrace(resp.getWriter());
                } catch (IOException e1) {
                }
            }
        };
        final String factoryClass = req.getParameter("factory");
        log.infof("Got factoryClassName %s in context %s", factoryClass, ctx);
        TransformerFactory factory = factoryClass == null ? TransformerFactory.newInstance() : TransformerFactory.newInstance(factoryClass, Thread.currentThread().getContextClassLoader());
        factory.setErrorListener(errorListener);
        URL xslUrl = this.getClass().getResource("/transform.xsl");
        Transformer t = null;
        try {
            try (InputStream inXsl = xslUrl.openStream()) {
                t = factory.newTransformer(new StreamSource(inXsl));
                Assert.assertNotNull(t);
                t.setErrorListener(errorListener);

                URL inXmlUrl = this.getClass().getResource("/input.xml");
                StringWriter out = new StringWriter();
                try (InputStream inXml = inXmlUrl.openStream()) {
                    t.transform(new StreamSource(inXml), new StreamResult(out));
                }
                String actual = out.toString().replace("utf-8", "UTF-8");
                URL outXmlUrl = this.getClass().getResource("/output.xml");
                String expected = FileUtils.readFile(outXmlUrl);
                Assert.assertEquals(expected, actual);

            }
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            resp.getWriter().write(t.getClass().getName());
        } catch (Exception e) {
            e.printStackTrace(resp.getWriter());
        }
    }

}
