package tacos.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class CorrelationIdFilterTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .addFilters(new CorrelationIdFilter()).build();
  }

  @Test
  void shouldGenerateUuidAndExposeSameIdToMdc() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      MvcResult result = mvc.perform(get("/probe"))
          .andExpect(status().isOk()).andReturn();
      String id = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
      UUID.fromString(id);
      assertEquals(id, result.getResponse().getContentAsString());
      assertEquals(id, logs.list.get(0).getMDCPropertyMap()
          .get(CorrelationContext.MDC_KEY));
      assertFalse(MDC.getCopyOfContextMap() != null
          && MDC.getCopyOfContextMap().containsKey(CorrelationContext.MDC_KEY));
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void shouldPreserveValidHeaderAndClearMdcBetweenRequests() throws Exception {
    MvcResult first = mvc.perform(get("/probe")
        .header(CorrelationIdFilter.HEADER, "client-123"))
        .andExpect(status().isOk()).andReturn();
    assertEquals("client-123", first.getResponse()
        .getHeader(CorrelationIdFilter.HEADER));
    assertEquals("client-123", first.getResponse().getContentAsString());
    assertEquals(null, MDC.get(CorrelationContext.MDC_KEY));

    MvcResult second = mvc.perform(get("/probe"))
        .andExpect(status().isOk()).andReturn();
    String secondId = second.getResponse().getHeader(CorrelationIdFilter.HEADER);
    UUID.fromString(secondId);
    assertNotEquals("client-123", secondId);
    assertEquals(secondId, second.getResponse().getContentAsString());
    assertEquals(null, MDC.get(CorrelationContext.MDC_KEY));
  }

  @Test
  void shouldReplaceNewlineAndOverlongValues() throws Exception {
    for (String input : new String[] {"evil\nforged", "x".repeat(101)}) {
      MvcResult result = mvc.perform(get("/probe")
          .header(CorrelationIdFilter.HEADER, input))
          .andExpect(status().isOk()).andReturn();
      String id = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
      UUID.fromString(id);
      assertNotEquals(input, id);
      assertEquals(id, result.getResponse().getContentAsString());
      assertTrue(id.length() <= 100);
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping("/probe")
    String probe() {
      return MDC.get(CorrelationContext.MDC_KEY);
    }
  }
}
