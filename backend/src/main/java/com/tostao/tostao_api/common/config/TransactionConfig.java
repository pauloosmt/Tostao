package com.tostao.tostao_api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * O advice transacional precisa envolver o {@code WorkspaceScopeAspect} (que usa
 * {@code Ordered.LOWEST_PRECEDENCE}), para que o contexto de workspace seja definido dentro da
 * transação. Por isso ele ganha uma ordem explicitamente mais prioritária. Se a ordem for invertida,
 * {@code TenantSession} falha com "só pode ser definido dentro de uma transação".
 */
@Configuration
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 100)
class TransactionConfig {
}
