package com.example.walletxfinance.config;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;

@Getter @Setter
@ConfigurationProperties(prefix="walletx.setu")
public class SetuProperties {
    private boolean enabled;
    private URI baseUrl;
    private URI tokenUrl;
    private URI redirectUrl;
    private String clientId="";
    private String clientSecret="";
    private String productId="";
    private String callbackToken="";
    private boolean pollingEnabled=true;
    private boolean allowLocalHttp;
    @PostConstruct void validate() {
        if (!enabled) return;
        for (URI uri : new URI[]{baseUrl,tokenUrl,redirectUrl}) {
            if (uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null ||
                    !("https".equals(uri.getScheme()) || (allowLocalHttp && "http".equals(uri.getScheme()) &&
                    java.util.List.of("localhost","127.0.0.1").contains(uri.getHost()))))
                throw new IllegalArgumentException("Setu URLs require HTTPS (loopback HTTP is test-only)");
        }
        if (baseUrl.getQuery()!=null || tokenUrl.getQuery()!=null || clientId.isBlank() || clientSecret.isBlank() || productId.isBlank() || callbackToken.length()<40)
            throw new IllegalArgumentException("Setu requires client ID/secret, product ID and a random callback token of at least 40 characters");
    }
}
