package mini_pjt3.com.team1.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    /**
     * GenericJackson2JsonRedisSerializer는 기본 생성자로 만들면 내부적으로 별도의
     * ObjectMapper를 새로 만드는데, 여기엔 LocalDateTime 등 자바 8 시간 타입을 다루는
     * JavaTimeModule이 등록돼 있지 않아 캐시 대상 객체에 LocalDateTime 필드가 있으면
     * 직렬화 시점에 예외가 난다. 그래서 모듈을 등록한 ObjectMapper를 직접 넘겨준다.
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    /**
     * Redis 연결을 위한 ConnectionFactory 설정
     * Lettuce 라이브러리를 사용하여 비동기로 Redis에 연결합니다.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(host, port);
    }

    /**
     * RedisTemplate 설정: 데이터를 저장하고 조회할 때 사용하는 메인 템플릿
     * 직렬화(Serializer)를 설정하지 않으면 Redis 데스크탑 매니저 등에서 
     * 데이터가 깨져 보이므로 반드시 설정해주는 것이 좋습니다.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        
        // Redis 연결 팩토리 설정
        redisTemplate.setConnectionFactory(redisConnectionFactory());

        // Key는 일반적인 String으로 직렬화
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());

        // Value는 JSON 형태로 직렬화하여 저장 (객체 저장 시 유용)
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper()));
        redisTemplate.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper()));

        return redisTemplate;
    }

    /**
     * 캐시용 CacheManager 설정.
     * 판매자 대시보드 결제 목록처럼 자주 조회되지만 쓰기 시점이 명확한 데이터를 캐싱하는 데 사용한다.
     * 기본 TTL(5분)은 쓰기 시점 무효화를 놓쳤을 때를 대비한 안전망이고,
     * 실제 무효화는 결제 상태가 바뀌는 시점에 코드에서 직접 evict하는 방식을 기본으로 한다.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}