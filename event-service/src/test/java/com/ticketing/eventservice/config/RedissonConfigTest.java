package com.ticketing.eventservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/** Redis가 죽었을 때 요청이 오래 매달리지 않게 하는 타임아웃·재시도 값이 실제로 Redisson 설정에 들어가는지 검사한다(연결하지 않음). */
class RedissonConfigTest {

    @Test
    void theTimeoutAndRetryValuesReachTheRedissonConfig() {
        Config config = RedissonConfig.buildConfig("redis-host", 6380, 1500, 1200, 1, 500);

        SingleServerConfig server = config.useSingleServer();
        assertThat(server.getAddress()).isEqualTo("redis://redis-host:6380");
        assertThat(server.getTimeout()).isEqualTo(1500);
        assertThat(server.getConnectTimeout()).isEqualTo(1200);
        assertThat(server.getRetryAttempts()).isEqualTo(1);
        assertThat(server.getRetryInterval()).isEqualTo(500);
    }

    @Test
    void theWorstCaseWaitIsMuchShorterThanTheRedissonDefaults() {
        // Redisson 기본값은 재시도 4번에 재시도 간격 1.5초였고, 장애 시험에서 요청 하나가 약 7초 뒤에 실패했다.
        // 우리가 고른 값은 그 절반도 되지 않아야 한다.
        SingleServerConfig ours = RedissonConfig.buildConfig("h", 6379, 1500, 1500, 1, 500).useSingleServer();

        long ourWorstCaseMs = (long) ours.getRetryInterval() * (ours.getRetryAttempts() + 1);
        long defaultWorstCaseMs = 1500L * (4 + 1);
        assertThat(ourWorstCaseMs).isLessThan(defaultWorstCaseMs / 3);
    }

    @Test
    void applicationYmlKeepsTheWorstCaseWaitForARefusedRedisUnderThreeSeconds() {
        // 실제로 서비스가 쓰는 application.yml을 읽는다. 설정 값을 Redisson 기본값(재시도 4번, 간격 1.5초 = 최악 7.5초)으로
        // 되돌리면 이 테스트가 실패해야 한다. buildConfig에 값을 직접 넘기는 위 테스트로는 그것을 알아챌 수 없다.
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new PropertiesPropertySource("application.yml", yaml.getObject()));
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        int timeoutMs = resolver.getRequiredProperty("redis.timeout-ms", Integer.class);
        int connectTimeoutMs = resolver.getRequiredProperty("redis.connect-timeout-ms", Integer.class);
        int retryAttempts = resolver.getRequiredProperty("redis.retry-attempts", Integer.class);
        int retryIntervalMs = resolver.getRequiredProperty("redis.retry-interval-ms", Integer.class);

        assertThat(timeoutMs).as("명령 타임아웃").isLessThanOrEqualTo(2000);
        assertThat(connectTimeoutMs).as("연결 타임아웃").isLessThanOrEqualTo(2000);
        assertThat((long) retryIntervalMs * (retryAttempts + 1)).as("재시도까지 합친 최악의 대기(ms)").isLessThanOrEqualTo(3000);
    }
}
