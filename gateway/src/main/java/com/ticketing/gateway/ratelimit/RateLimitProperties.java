package com.ticketing.gateway.ratelimit;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 rate-limit.* 설정이다. 클라이언트별 기본값과, 악용되기 쉬운 엔드포인트를 위한 더 엄격한 규칙이다. */
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Limit global = new Limit();
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Limit getGlobal() {
        return global;
    }

    public void setGlobal(Limit global) {
        this.global = global;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static class Limit {
        private int capacity = 600;
        private int perMinute = 600;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }
    }

    public static class Rule extends Limit {
        private String name;
        /** 비어 있으면 모든 메서드다. */
        private List<String> methods = new ArrayList<>();
        /** Ant 스타일 패턴이다. 예: /api/orders/*&#47;transfer. */
        private List<String> paths = new ArrayList<>();
        /** 정상 클라이언트가 계속 두드리는 엔드포인트(대기열 폴링)용이다. 이것까지 전체 제한에 올리지 않는다. */
        private boolean skipGlobal;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getMethods() {
            return methods;
        }

        public void setMethods(List<String> methods) {
            this.methods = methods;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public boolean isSkipGlobal() {
            return skipGlobal;
        }

        public void setSkipGlobal(boolean skipGlobal) {
            this.skipGlobal = skipGlobal;
        }
    }
}
