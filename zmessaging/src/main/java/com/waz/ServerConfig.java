/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz;

/**
 * 统一修改域名等配置信息
 */
public class ServerConfig {

    /**
     * {@link com.waz.znet.ClientWrapper val domains @ Seq(zinfra, wire) = Seq(ServerConfig.getBASE_URL_PREFIX(), ServerConfig.getBASE_URL_POSTFIX())
     */
    private static String BASE_URL_PREFIX ;//= "account";
    /**
     * {@link com.waz.znet.ClientWrapper val domains @ Seq(zinfra, wire) = Seq(ServerConfig.getBASE_URL_PREFIX(), ServerConfig.getBASE_URL_POSTFIX())
     */
    private static String BASE_URL_POSTFIX ;//= "isecret.im";

    /**
     * tests/unit 下面 {@link com.waz.service.media RichMediaContentParserSpec}
     * <p>
     * account.isecret.im
     */
    private static String BASE_URL_DOMAINS ;//= new StringBuilder().append(BASE_URL_PREFIX).append('.').append(BASE_URL_POSTFIX).toString();

    /**
     * http://account.isecret.im
     */
    private static String BASE_URL_HTTP ;//= new StringBuilder().append("http://").append(BASE_URL_DOMAINS).toString();

    /**
     * https://account.isecret.im
     */
    private static String BASE_URL_HTTPS ;//= new StringBuilder().append("https://").append(BASE_URL_DOMAINS).toString();


    public static final void setParams(String BASE_URL_PREFIX, String BASE_URL_POSTFIX) {
        ServerConfig.BASE_URL_PREFIX = BASE_URL_PREFIX;
        ServerConfig.BASE_URL_POSTFIX = BASE_URL_POSTFIX;
        ServerConfig.BASE_URL_DOMAINS = new StringBuilder().append(BASE_URL_PREFIX).append('.').append(BASE_URL_POSTFIX).toString();
        ServerConfig.BASE_URL_HTTP = new StringBuilder().append("http://").append(ServerConfig.BASE_URL_DOMAINS).toString();
        ServerConfig.BASE_URL_HTTPS = new StringBuilder().append("https://").append(ServerConfig.BASE_URL_DOMAINS).toString();
    }

    public static final void setAllParams(String BASE_URL_PREFIX, String BASE_URL_POSTFIX, String BASE_URL_DOMAINS, String BASE_URL_HTTP, String BASE_URL_HTTPS) {
        ServerConfig.BASE_URL_PREFIX = BASE_URL_PREFIX;
        ServerConfig.BASE_URL_POSTFIX = BASE_URL_POSTFIX;
        ServerConfig.BASE_URL_DOMAINS = BASE_URL_DOMAINS;
        ServerConfig.BASE_URL_HTTP = BASE_URL_HTTP;
        ServerConfig.BASE_URL_HTTPS = BASE_URL_HTTPS;
    }

    public static String getBASE_URL_PREFIX() {
        return BASE_URL_PREFIX;
    }

    public static String getBASE_URL_POSTFIX() {
        return BASE_URL_POSTFIX;
    }

    public static String getBASE_URL_DOMAINS() {
        return BASE_URL_DOMAINS;
    }

    public static String getBASE_URL_HTTP() {
        return BASE_URL_HTTP;
    }

    public static String getBASE_URL_HTTPS() {
        return BASE_URL_HTTPS;
    }
}
