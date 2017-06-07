package net.dongliu.byproxy.parser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * @author Liu Dong
 */
public class HttpInputStreamTest {
    @Test
    public void readLine() throws Exception {
        HttpInputStream inputStream = new HttpInputStream(new ByteArrayInputStream("test\r123\n\r\n\r\n".getBytes()));
        String line = inputStream.readLine();
        assertEquals("test\r123\n", line);

        line = inputStream.readLine();
        assertEquals("", line);

        line = inputStream.readLine();
        assertNull(line);
    }


    private void mock() throws IOException {
        RequestHeaders requestHeaders = mockRequest();
        ResponseHeaders responseHeaders = mockResponse();
    }

    private RequestHeaders mockRequest() throws IOException {
        InputStream input = getClass().getResourceAsStream("/req.txt");
        HttpInputStream httpInputStream = new HttpInputStream(input);
        return httpInputStream.readRequestHeaders();
    }

    private ResponseHeaders mockResponse() throws IOException {
        InputStream input = getClass().getResourceAsStream("/resp.txt");
        HttpInputStream httpInputStream = new HttpInputStream(input);
        return httpInputStream.readResponseHeaders();
    }
}