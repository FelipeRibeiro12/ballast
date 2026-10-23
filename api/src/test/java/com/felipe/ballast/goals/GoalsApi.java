package com.felipe.ballast.goals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Rotas de metas como o cliente faria.
final class GoalsApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final MockMvc mvc;

  GoalsApi(MockMvc mvc) {
    this.mvc = mvc;
  }

  Cookie newSession() throws Exception {
    TestUsers users = new TestUsers(mvc);
    return users.session(users.register());
  }

  static Map<String, Object> goal(String title, long targetCents) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("titulo", title);
    body.put("valorAlvoCentavos", targetCents);
    return body;
  }

  static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.put(key, value);
    return copy;
  }

  MockHttpServletResponse create(Cookie session, Object body) throws Exception {
    return send(session, post("/metas"), body);
  }

  JsonNode createOk(Cookie session, Object body) throws Exception {
    return body(expect(create(session, body), 201));
  }

  MockHttpServletResponse update(Cookie session, String id, Object body) throws Exception {
    return send(session, put("/metas/" + id), body);
  }

  MockHttpServletResponse remove(Cookie session, String id) throws Exception {
    return send(session, delete("/metas/" + id), null);
  }

  MockHttpServletResponse list(Cookie session) throws Exception {
    return send(session, get("/metas"), null);
  }

  JsonNode listOk(Cookie session) throws Exception {
    return body(expect(list(session), 200));
  }

  MockHttpServletResponse deposit(Cookie session, String goalId, long cents, String date)
      throws Exception {
    return send(
        session,
        post("/metas/" + goalId + "/aportes"),
        Map.of("valorCentavos", cents, "data", date));
  }

  JsonNode depositOk(Cookie session, String goalId, long cents, String date) throws Exception {
    return body(expect(deposit(session, goalId, cents, date), 201));
  }

  MockHttpServletResponse deposits(Cookie session, String goalId) throws Exception {
    return send(session, get("/metas/" + goalId + "/aportes"), null);
  }

  MockHttpServletResponse removeDeposit(Cookie session, String goalId, String depositId)
      throws Exception {
    return send(session, delete("/metas/" + goalId + "/aportes/" + depositId), null);
  }

  // body nulo manda sem corpo; String vai como está, o resto vira JSON.
  MockHttpServletResponse send(Cookie session, MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    if (session != null) {
      request.cookie(session);
    }
    if (body != null) {
      request
          .contentType(MediaType.APPLICATION_JSON)
          .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  static MockHttpServletResponse expect(MockHttpServletResponse response, int status)
      throws Exception {
    if (response.getStatus() != status) {
      throw new AssertionError(
          "esperava "
              + status
              + " e veio "
              + response.getStatus()
              + ": "
              + response.getContentAsString(StandardCharsets.UTF_8));
    }
    return response;
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  static String error(MockHttpServletResponse response) throws Exception {
    return body(response).get("erro").asString();
  }
}
