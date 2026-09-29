package aw.httphandler;

import aw.config.ProxyConfig;
import aw.config.ProxyConfig.Model;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET /v1/models => 模型元数据（id、provider、max_tokens、context_length、capability，来自 config.yaml）
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

    List<Model> models = proxyConfig.getModels();

    if (models == null) {
      jsonObject.put("data", List.of());
    }
    else {
      List<JSONObject> modelsJson = models.stream().map(model -> {
        JSONObject modelJson = new JSONObject();
        modelJson.put("id", model.getName());
        modelJson.put("object", "model");
        // owned_by 取首条 upstream 的 provider（多条 upstream 时仅作展示，实际命中由 lb_policy 决定）
        List<ProxyConfig.Upstream> upstreams = model.getUpstream();
        modelJson.put("owned_by",
            upstreams == null || upstreams.isEmpty() ? null : upstreams.get(0).getProvider());
        modelJson.put("created", 758044800);
        // 元数据字段直出配置（未配置为 null），客户端可据此选型
        modelJson.put("max_tokens", model.getMaxTokens());
        modelJson.put("context_length", model.getContextLength());
        modelJson.put("capability", model.getCapability());
        return modelJson;
      }).toList();

      jsonObject.put("data", modelsJson);
    }

    ctx.status(200).contentType("application/json").result(jsonObject.toJSONString());
  }
}
