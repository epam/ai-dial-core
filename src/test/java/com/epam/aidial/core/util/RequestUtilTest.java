package com.epam.aidial.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestUtilTest {

  @Test
  public void testDileFormatConversion_OpenApiRequest() throws JsonProcessingException {
    //GIVEN
    ObjectNode openApiFormat = (ObjectNode) JsonMapper.builder().build().readTree("""
        {
            "messages": [
              {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text":  "what is on image"
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": "files/BbuBnPpLQCQDS6FYd78GdMXnZwfw7oqufMiEk9ESdV47/img/apple.jpeg"
                        }
                    }
                ]
              }
            ],
            "temperature": 0.1,
            "stream":false
        }
        """);

    //WHEN
    ObjectNode convertedResult = RequestUtil.convertToDialFormat(openApiFormat);

    //THEN
    assertEquals(
        """
            {"messages":[{"role":"user","content":"what is on image","custom_content":{"attachments":[{"type":"image/png","url":"files/BbuBnPpLQCQDS6FYd78GdMXnZwfw7oqufMiEk9ESdV47/img/apple.jpeg"}]}}],"temperature":0.1,"stream":false}""",
        convertedResult.toString());
  }

  @Test
  public void testDileFormatConversion_DialRequest() throws JsonProcessingException {
    //GIVEN
    ObjectNode dialFormat = (ObjectNode) JsonMapper.builder().build().readTree("""
        {
          "messages": [
            {
              "role": "user",
              "content": "what is on image",
              "custom_content": {
                "attachments": [
                  {
                    "type": "image/png",
                    "url": "files/BbuBnPpLQCQDS6FYd78GdMXnZwfw7oqufMiEk9ESdV47/img/apple.jpeg"
                  }
                ]
              }
            }
          ]
        }
        """);

    //WHEN
    ObjectNode convertedResult = RequestUtil.convertToDialFormat(dialFormat);

    //THEN
    assertEquals(
        """
            {"messages":[{"role":"user","content":"what is on image","custom_content":{"attachments":[{"type":"image/png","url":"files/BbuBnPpLQCQDS6FYd78GdMXnZwfw7oqufMiEk9ESdV47/img/apple.jpeg"}]}}]}""",
        convertedResult.toString());
  }

}
