package com.orinuno.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikProxy {

    private Long id;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private ProxyType proxyType;
    private ProxyStatus status;
    private LocalDateTime lastUsedAt;
    private Integer failCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum ProxyType {
        HTTP, SOCKS5
    }

    public enum ProxyStatus {
        ACTIVE, DISABLED, FAILED
    }
}
