package io.github.shubhamjaggi.relay.spring;

import io.github.shubhamjaggi.relay.RelayGroup;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AOP aspect that implements the {@link Coalesce} annotation by intercepting
 * annotated method calls and routing them through a {@link RelayGroup}.
 *
 * <h2>Key design</h2>
 * <p>Every annotated method gets a unique namespace in the shared
 * {@link RelayGroup}. The deduplication key passed to the group is:
 * <pre>
 *   methodSignature + "::" + evaluatedSpelValue
 * </pre>
 * For example, {@code public java.lang.String com.example.UserService.fetchProfile(java.lang.String)::user-42}.
 *
 * <p>This namespace prevents accidental collision between two different methods
 * that happen to use the same SpEL key expression and produce the same value.
 * Without it, {@code fetchProfile("42")} and {@code fetchOrder("42")} would
 * share a single execution — clearly wrong.
 *
 * <h2>Shared group</h2>
 * <p>A single {@link RelayGroup}{@code <Object>} instance handles all
 * annotated methods on all beans. This is safe because keys are namespaced by
 * method signature (see above). A shared group is more efficient than one group
 * per method because it avoids the overhead of tracking and registering many
 * instances.
 *
 * <h2>SpEL expression caching</h2>
 * <p>Parsing a SpEL expression on every method invocation is expensive. Parsed
 * {@link Expression} objects are cached in a {@link ConcurrentHashMap} keyed by
 * {@code methodSignature + "::" + spelString}. This makes repeated invocations
 * cheap — parsing happens at most once per distinct method+expression combination.
 *
 * <h2>Exception propagation</h2>
 * <p>{@link RelayGroup#execute} accepts a {@link java.util.concurrent.Callable}
 * which can only declare {@code throws Exception}. However, the intercepted
 * {@link ProceedingJoinPoint#proceed()} can throw any {@link Throwable} — including
 * {@link Error} subclasses. To preserve the original throwable type across this
 * boundary, all throwables are wrapped in a private {@link WrappedThrowable}
 * (a {@link RuntimeException}) before crossing into the {@code Callable}, and
 * unwrapped immediately after. This ensures callers always see the original exception,
 * not a wrapper.
 *
 * <h2>Thread safety</h2>
 * <p>This aspect is thread-safe. All mutable state ({@code inFlight} map inside
 * {@link RelayGroup}, and {@code expressionCache}) uses concurrent data
 * structures. The {@link SpelExpressionParser} and {@link DefaultParameterNameDiscoverer}
 * are stateless after construction and safe to share across threads.
 *
 * @see Coalesce
 * @see RelayGroup
 */
@Aspect
public class CoalesceAspect {

    /** Deduplicates concurrent executions across all @Coalesce-annotated methods. */
    private final RelayGroup<Object> group = new RelayGroup<>();

    /** Parses SpEL expressions from the {@code key} attribute. Stateless after construction. */
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * Resolves method parameter names so SpEL expressions like {@code #userId} can
     * reference parameters by the name written in source code. Requires the class
     * to be compiled with debug info ({@code -parameters} flag or debug symbols),
     * which is the default in Maven.
     */
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * Cache of parsed SpEL {@link Expression} objects.
     * Key: {@code methodSignature + "::" + spelString} — unique per method+expression pair.
     * Parsing each expression once and reusing it avoids repeated lexing and AST construction.
     */
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Intercepts calls to {@link Coalesce}-annotated methods and routes them through
     * the shared {@link RelayGroup}.
     *
     * <p>This method is the single entry point for all coalesced calls. It:
     * <ol>
     *   <li>Evaluates the SpEL {@code key} expression against the actual arguments</li>
     *   <li>Prepends the method signature to namespace the key</li>
     *   <li>Delegates to {@link RelayGroup#execute} with the composite key</li>
     *   <li>Unwraps any {@link WrappedThrowable} to restore the original exception type</li>
     * </ol>
     *
     * @param pjp      the join point representing the intercepted method call
     * @param coalesce the {@link Coalesce} annotation on the intercepted method
     * @return the result of the method, from this execution or a concurrent one
     * @throws Throwable the original exception thrown by the method, propagated to all callers
     */
    @Around("@annotation(coalesce)")
    public Object around(ProceedingJoinPoint pjp, Coalesce coalesce) throws Throwable {
        String compositeKey = buildKey(pjp, coalesce);
        try {
            return group.execute(compositeKey, () -> {
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    // pjp.proceed() can throw any Throwable, but Callable only allows Exception.
                    // Wrap it so it can cross the Callable boundary without type erasure.
                    throw new WrappedThrowable(t);
                }
            });
        } catch (WrappedThrowable e) {
            // Unwrap to give the caller their original exception, not a RuntimeException wrapper.
            throw e.getCause();
        }
    }

    /**
     * Builds the composite deduplication key for the given invocation.
     *
     * <p>The key format is: {@code methodSignature + "::" + evaluatedSpelValue}.
     * Example: {@code "public java.lang.String com.example.UserService.fetchProfile(java.lang.String)::user-42"}.
     *
     * <p>Using the full generic method signature as a prefix guarantees that two
     * different methods with the same SpEL expression and the same runtime argument
     * value will still produce different keys and will not interfere with each other.
     *
     * @param pjp      the join point to extract method metadata from
     * @param coalesce the annotation holding the SpEL key expression
     * @return the composite deduplication key string
     */
    private String buildKey(ProceedingJoinPoint pjp, Coalesce coalesce) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        String methodId = method.toGenericString();

        // Retrieve a cached parsed expression, or parse and cache it on first use.
        Expression expr = expressionCache.computeIfAbsent(
                methodId + "::" + coalesce.key(),
                k -> parser.parseExpression(coalesce.key())
        );

        // Evaluate the expression with the actual arguments bound by parameter name.
        MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(
                pjp.getTarget(), method, pjp.getArgs(), nameDiscoverer);

        Object keyVal = expr.getValue(ctx);
        return methodId + "::" + keyVal;
    }

    /**
     * A private {@link RuntimeException} used to carry any {@link Throwable} across
     * the {@link java.util.concurrent.Callable} boundary, which only permits {@code Exception}.
     *
     * <p>This is an implementation detail. It is never visible to callers —
     * the {@link #around} method always unwraps it before rethrowing.
     */
    private static final class WrappedThrowable extends RuntimeException {
        WrappedThrowable(Throwable cause) {
            super(cause);
        }
    }
}
