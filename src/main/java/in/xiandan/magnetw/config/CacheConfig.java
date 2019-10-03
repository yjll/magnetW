package in.xiandan.magnetw.config;

import com.google.common.cache.CacheBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.guava.GuavaCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String MAGNET_LIST_KEY = "magnetList";
    public static final String MAGNET_DETAIL_KEY = "magnetDetail";

    @Bean
    public CacheManager cacheManager(){
        SimpleCacheManager simpleCacheManager = new SimpleCacheManager();
        GuavaCache listCache = new GuavaCache(MAGNET_LIST_KEY, CacheBuilder.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(100).build());
        GuavaCache detailCache = new GuavaCache(MAGNET_DETAIL_KEY, CacheBuilder.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(1000).build());

        simpleCacheManager.setCaches(Arrays.asList(listCache,detailCache));

        return simpleCacheManager;

    }
}
