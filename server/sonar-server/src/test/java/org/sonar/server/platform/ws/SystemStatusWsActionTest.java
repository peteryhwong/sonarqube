package org.sonar.server.platform.ws;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonar.api.platform.Server;
import org.sonar.api.platform.ServerUpgradeStatus;
import org.sonar.api.server.ws.Request;
import org.sonar.server.platform.monitoring.DatabaseMonitor;
import org.sonar.server.ws.WsTester;
import org.sonar.test.JsonAssert;

import static org.mockito.Mockito.when;

public class SystemStatusWsActionTest {

  private static final String SERVER_ID = "server_id";
  private static final String SERVER_VERSION = "server_version";

  @Mock
  Server server;
  @Mock
  ServerUpgradeStatus serverUpgradeStatus;
  @Mock
  DatabaseMonitor databaseMonitor;
  @Mock
  Request request;
  @InjectMocks
  SystemStatusWsAction underTest;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void id_and_version_in_json_are_values_of_server() throws Exception {
    mockServer();

    WsTester.TestResponse response = new WsTester.TestResponse();
    underTest.handle(request, response);

    JsonAssert.assertJson(response.outputAsString()).isSimilarTo(expectedJson("DOWN"));
  }

  private void mockServer() {
    when(server.getId()).thenReturn(SERVER_ID);
    when(server.getVersion()).thenReturn(SERVER_VERSION);
  }

  private static String expectedJson(String status) {
    return "{" +
        "  \"id\": \"" + SERVER_ID + "\",\n" +
        "  \"version\": \"" + SERVER_VERSION + "\",\n" +
        "  \"status\": \"" + status + "\"\n" +
        "}";
  }
}
