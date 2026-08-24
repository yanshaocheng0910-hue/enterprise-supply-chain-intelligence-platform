package com.scic.platform.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("企业供应链数智化协同平台 API")
                .version("0.9.0-uat-candidate")
                .description("Spring Boot 业务服务为唯一业务数据读写入口；FastAPI 仅通过内部接口提供预测与受控语义解析。"));
    }
}
