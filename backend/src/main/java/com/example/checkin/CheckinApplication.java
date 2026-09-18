package com.example.checkin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 习惯打卡应用的 Spring Boot 启动入口。
 *
 * <p>这个类负责启动应用和装配 Spring 容器，不处理登录、打卡或数据库读写等业务。
 * 后续 HTTP 请求由 Controller 接收，业务规则放在 Service，数据库访问由 Mapper 完成。
 *
 * <p>{@link SpringBootApplication} 主要组合了以下能力：
 * <ul>
 *     <li>配置类声明：将当前类作为 Spring Boot 的主要配置入口。</li>
 *     <li>自动配置：根据依赖、配置属性和已有 Bean，按条件装配 Web、数据源等组件。
 *         例如，本项目的 Web 依赖支持启动内嵌 Web 服务器，MyBatis Starter
 *         为数据库访问提供相应的自动配置。</li>
 *     <li>组件扫描：默认从当前类所在的 {@code com.example.checkin} 包向下扫描，
 *         注册带有 Controller、Service、Configuration 等组件注解的类。
 *         因此项目的后端组件应放在该包或其子包中。</li>
 * </ul>
 *
 * <p>Mapper 接口的代理由 MyBatis 的扫描和注册机制创建；本项目通过接口上的
 * {@code @Mapper} 标记配合 MyBatis Starter 自动发现 Mapper，
 * 并不是由普通组件扫描直接实例化接口。
 */
@SpringBootApplication
public class CheckinApplication {

    /**
     * Java 程序入口，可由 IDEA 或 {@code java -jar} 调用。
     *
     * @param args 启动时传入的命令行参数，例如 {@code --server.port=8081}；
     *             这些参数会交给 Spring Boot 参与配置解析
     */
    public static void main(String[] args) {
        // 以当前类作为主要配置源，创建并刷新 Spring 应用上下文。
        // 启动过程中会读取 application.yml，并结合环境变量、命令行参数等解析配置，
        // 然后执行自动配置、注册组件并完成依赖注入。
        // 在当前 Web 应用配置下，还会启动内嵌服务器，使应用能够接收 HTTP 请求。
        // 此调用不会自动执行 database/init.sql；本项目的建表脚本需要显式运行。
        SpringApplication.run(CheckinApplication.class, args);
    }
}
