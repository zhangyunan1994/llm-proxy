package aw.httphandler;

import aw.config.ConfigLoader.ProxyConfig;
import aw.config.ConfigLoader.ProxyConfig.Model;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET /v1/models => Model metadata (id, context size, parameters)
 */
public class ModelsHttpHandler {
  private static final Logger log = LoggerFactory.getLogger(ModelsHttpHandler.class);

  private final ProxyConfig proxyConfig;

  public ModelsHttpHandler(ProxyConfig proxyConfig) {
    this.proxyConfig = proxyConfig;
  }

  public void handle(Context ctx) {
    JSONObject jsonObject = new JSONObject();

    jsonObject.put("object", "list");
    jsonObject.put("success", true);

    List<Model> models = proxyConfig.models();

    if (models == null) {
      jsonObject.put("data", List.of());
    }
    else {
      List<JSONObject> modelsJson = models.stream().map(model -> {
        JSONObject modelJson = new JSONObject();
        modelJson.put("id", model.name());
        modelJson.put("object", "model");
        modelJson.put("owned_by", model.provider());
        modelJson.put("created", 758044800);
        modelJson.put("shutdown_date", null);
        return modelJson;
      }).toList();

      jsonObject.put("data", modelsJson);
    }

    ctx.status(200).result(jsonObject.toJSONString());
  }
}
