package com.ticketing.gateway.ratelimit;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The rate-limit.* settings in application.yml: a per-client default plus stricter rules for abuse-prone endpoints. */
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
        /** Empty means any method. */
        private List<String> methods = new ArrayList<>();
        /** Ant-style patterns, e.g. /api/orders/*&#47;transfer. */
        private List<String> paths = new ArrayList<>();
        /** For endpoints a normal client hits constantly (queue polling): don't also charge them to the global limit. */
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
