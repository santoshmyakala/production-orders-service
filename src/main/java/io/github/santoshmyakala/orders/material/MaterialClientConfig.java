package io.github.santoshmyakala.orders.material;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class MaterialClientConfig {

    /** Explicit connect and read timeouts: without them a hung ERP holds request threads forever. */
    @Bean
    RestClient erpRestClient(RestClient.Builder builder,
                             @Value("${orders.erp.base-url}") String baseUrl,
                             @Value("${orders.erp.connect-timeout:1s}") Duration connectTimeout,
                             @Value("${orders.erp.read-timeout:2s}") Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
