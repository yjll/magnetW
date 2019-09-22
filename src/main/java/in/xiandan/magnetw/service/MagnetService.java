package in.xiandan.magnetw.service;


import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

import org.apache.log4j.Logger;
import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.DomSerializer;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import in.xiandan.magnetw.config.ApplicationConfig;
import in.xiandan.magnetw.exception.MagnetParserException;
import in.xiandan.magnetw.request.DefaultSslSocketFactory;
import in.xiandan.magnetw.response.MagnetItem;
import in.xiandan.magnetw.response.MagnetItemDetail;
import in.xiandan.magnetw.response.MagnetPageOption;
import in.xiandan.magnetw.response.MagnetPageSiteSort;
import in.xiandan.magnetw.response.MagnetRule;
import in.xiandan.magnetw.response.MagnetRuleDetail;

/**
 * created 2018/3/6 16:04
 */
@EnableAsync
@Service
public class MagnetService {
    private Logger logger = Logger.getLogger(getClass());

    @Autowired
    private MagnetRuleService ruleService;

    @Autowired
    private ApplicationConfig config;

    @Autowired
    @Lazy
    private MagnetService magnetService;

    @CacheEvict(value = {"magnetList", "magnetDetail"}, allEntries = true)
    public void clearCache() {
        logger.info("列表缓存清空");
    }


    /**
     * 修正参数
     *
     * @param sourceParam
     * @param keyword
     * @param sortParam
     * @param pageParam
     * @return
     */
    public MagnetPageOption transformCurrentOption(String sourceParam, String keyword,
                                                   String sortParam, Integer pageParam) {
        MagnetPageOption option = new MagnetPageOption();

        option.setKeyword(keyword);
        int page = pageParam == null || pageParam <= 0 ? 1 : pageParam;
        option.setPage(page);

        //如果有这个网站规则 就使用 没有就取第一个
        MagnetRule source = ruleService.getRuleBySite(sourceParam);
        option.setSite(source.getSite());

        //如果支持这个排序 不支持就取第一个排序
        List<MagnetPageSiteSort> supportedSorts = ruleService.getSupportedSorts(source.getPaths());
        for (MagnetPageSiteSort item : supportedSorts) {
            if (item.getSort().equals(sortParam)) {
                option.setSort(item.getSort());
                break;
            }
        }
        if (StringUtils.isEmpty(option.getSort())) {
            option.setSort(supportedSorts.get(0).getSort());
        }

        String url = formatSiteUrl(source, option.getKeyword(), option.getSort(), option.getPage());
        option.setSiteUrl(url);
        return option;
    }


    /**
     * 请求源站
     *
     * @param url
     * @param site      源站名称
     * @param host      源站的域名
     * @param isProxy   是否使用代理
     * @param userAgent
     * @return
     * @throws IOException
     */
    protected Document requestSourceSite(String url, String site, String host, boolean isProxy, String userAgent) throws IOException, ParserConfigurationException {
        Connection connect = Jsoup.connect(url)
                .ignoreContentType(true)
                .sslSocketFactory(DefaultSslSocketFactory.getDefaultSslSocketFactory())
                .timeout((int) config.sourceTimeout)
                .header(HttpHeaders.HOST, host);
        //增加userAgent
        if (StringUtils.isEmpty(userAgent)) {
            connect.header(HttpHeaders.USER_AGENT, userAgent);
        }

        //代理设置
        if (config.proxyEnabled && isProxy) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(config.proxyHost, config.proxyPort));
            connect.proxy(proxy);
        }

        StringBuffer log = new StringBuffer();
        log.append("正在请求--->");
        log.append(site);
        log.append("--->");
        log.append(Thread.currentThread().getName());
        log.append("\n");
        log.append(url);
        log.append("\n[Request Headers]\n");
        Map<String, String> headers = connect.request().headers();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String header = entry.getKey();
            log.append(header);
            log.append(":");
            log.append(headers.get(header));
            log.append("\n");
        }
//        logger.info(log.toString());

        Connection.Response response = connect.execute();

        String html = response.parse().html();

        TagNode node = new HtmlCleaner().clean(html);
        return new DomSerializer(new CleanerProperties()).createDOM(node);
    }

    public String formatSiteUrl(MagnetRule rule, String keyword, String sort, int page) {
        if (StringUtils.isEmpty(keyword)) {
            return rule.getUrl();
        }
        //用页码和关键字 拼接源站的url
        //部分源站顺序不一致，2.1.1以后使用替换的形式
        String sortPath = ruleService.getPathBySort(sort, rule.getPaths()).replace("%s", keyword).replace("%d", String.valueOf(page));
        return String.format("%s%s", rule.getUrl(), sortPath);
    }

    @Cacheable(value = "magnetList", key = "T(String).format('%s-%s-%s-%d',#rule.url,#keyword,#sort,#page)")
    public List<MagnetItem> parser(MagnetRule rule, String keyword, String sort, int page, String userAgent) throws MagnetParserException, IOException {
        if (StringUtils.isEmpty(keyword)) {
            return new ArrayList<MagnetItem>();
        }

        String url = formatSiteUrl(rule, keyword, sort, page);

        //请求源站
        try {
            Document dom = requestSourceSite(url, rule.getSite(), rule.getHost(), rule.isProxy(), userAgent);

            List<MagnetItem> infos = new ArrayList<MagnetItem>();
            XPath xPath = XPathFactory.newInstance().newXPath();

            //列表
            NodeList result = (NodeList) xPath.evaluate(rule.getGroup(), dom, XPathConstants.NODESET);
            for (int i = 0; i < result.getLength(); i++) {
                Node node = result.item(i);
                if (node != null) {
                    if (StringUtils.isEmpty(node.getTextContent().trim())) {
                        continue;
                    }
                    MagnetItem info = new MagnetItem();
                    //磁力链
                    Node magnetNote = (Node) xPath.evaluate(rule.getMagnet(), node, XPathConstants.NODE);
                    if (magnetNote != null) {
                        String magnetValue = magnetNote.getTextContent();
                        info.setMagnet(transformMagnet(magnetValue));
                    }
                    //名称
                    NodeList nameNotes = ((NodeList) xPath.evaluate(rule.getName(), node, XPathConstants.NODESET));
                    if (nameNotes != null && nameNotes.getLength() > 0) {
                        //少数名称有可能会找到多个 默认取最后一个 比如Nyaa
                        Node nameNote = nameNotes.item(nameNotes.getLength() - 1);

                        String nameValue = nameNote.getTextContent();
                        info.setName(nameValue);
                        //高亮关键字 兼容大小写
                        int keywordIndex = nameValue.toLowerCase().indexOf(keyword.toLowerCase());
                        if (keywordIndex >= 0) {
                            StringBuilder buffer = new StringBuilder(nameValue);
                            buffer.insert(keywordIndex + keyword.length(), "</span>");
                            buffer.insert(keywordIndex, "<span style=\"color:#ff7a76\">");
                            info.setNameHtml(buffer.toString());
                        } else {
                            info.setNameHtml(nameValue.replace(keyword, String.format("<span style=\"color:#ff7a76\">%s</span>", keyword)));
                        }

                        Node hrefAttr = nameNote.getAttributes().getNamedItem("href");
                        if (hrefAttr != null) {
                            info.setDetailUrl(transformDetailUrl(rule.getUrl(), hrefAttr.getTextContent()));
                        }

                        //一些加工的额外信息
                        String resolution = transformResolution(nameValue);
                        info.setResolution(resolution);
                    }
                    //大小
                    Node sizeNote = ((Node) xPath.evaluate(rule.getSize(), node, XPathConstants.NODE));
                    if (sizeNote != null) {
                        String sizeValue = sizeNote.getTextContent();
                        info.setFormatSize(sizeValue);
                        info.setSize(transformSize(sizeValue));
                    }
                    //时间
                    Node countNode = (Node) xPath.evaluate(rule.getDate(), node, XPathConstants.NODE);
                    if (countNode != null) {
                        info.setDate(countNode.getTextContent());
                    }
                    //人气/热度
                    if (!StringUtils.isEmpty(rule.getHot())) {
                        Node popularityNode = (Node) xPath.evaluate(rule.getHot(), node, XPathConstants.NODE);
                        if (popularityNode != null) {
                            info.setHot(popularityNode.getTextContent());
                        }
                    }

                    if (!StringUtils.isEmpty(info.getName())) {
                        infos.add(info);
                    }
                }
            }
            return infos;
        } catch (HttpStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND.value() || e.getStatusCode() == HttpStatus.FORBIDDEN.value()) {
                return new ArrayList<MagnetItem>();
            } else {
                throw new MagnetParserException(e);
            }
        } catch (Exception e) {
            throw new MagnetParserException(e);
        }
    }


    /**
     * 异步加载下一页
     */
    @Async
    public void asyncPreloadNextPage(MagnetRule rule, MagnetPageOption current, String userAgent) {
        try {
            int page = current.getPage() + 1;
            List<MagnetItem> itemList = magnetService.parser(rule, current.getKeyword(), current.getSort(), page, userAgent);

            logger.info(String.format("成功预加载 %s-%s-%d，缓存%d条数据", rule.getName(), current.getKeyword(), page, itemList.size()));
        } catch (MagnetParserException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Async
    public void asyncPreloadOtherPage(MagnetPageOption current, String userAgent) {
        List<MagnetRule> sites = ruleService.getSites();
        for (MagnetRule site : sites) {
            try {
                List<MagnetItem> itemList = magnetService.parser(site, current.getKeyword(), current.getSort(), current.getPage(), userAgent);
                logger.info(String.format("成功预加载 %s-%s-%d，缓存%d条数据", site.getSite(), current.getKeyword(), current.getPage(), itemList.size()));
            } catch (Exception e) {
            }
        }
    }


    /**
     * 解析源站详情
     *
     * @param detailUrl 源站详情url
     * @param rule
     * @param userAgent
     * @return
     * @throws MagnetParserException
     */
    @Cacheable(value = "magnetDetail", key = "#detailUrl")
    public MagnetItemDetail parserDetail(String detailUrl, MagnetRule rule, String userAgent) throws MagnetParserException {
        try {
            MagnetRuleDetail detail = rule.getDetail();
            if (detail == null) {
                throw new NullPointerException("此源站没有配置详情规则");
            }
            Document dom = requestSourceSite(detailUrl, rule.getSite(), rule.getHost(), rule.isProxy(), userAgent);
            XPath xPath = XPathFactory.newInstance().newXPath();

            MagnetItemDetail result = new MagnetItemDetail();
            //文件列表
            List<String> files = new ArrayList<String>();
            NodeList fileNodeList = (NodeList) xPath.evaluate(detail.getFiles(), dom, XPathConstants.NODESET);
            for (int i = 0; i < fileNodeList.getLength(); i++) {
                Node item = fileNodeList.item(i);
                if (item != null) {
                    files.add(item.getTextContent().trim());
                }
            }
            result.setFiles(files);
            return result;
        } catch (Exception e) {
            throw new MagnetParserException(e);
        }
    }

    private String transformDetailUrl(String url, String magnetValue) {
        return magnetValue.startsWith("http") ? magnetValue : url + magnetValue;
    }

    /**
     * 磁力链转换
     * 检查url是否磁力链，不是的话手动拼接磁力链
     *
     * @param url
     * @return
     */
    private String transformMagnet(String url) {
        if (StringUtils.isEmpty(url)) {
            return url;
        }
        String regex = "magnet:?[^\\\"]+";
        boolean matches = Pattern.matches(regex, url);
        if (matches) {
            return url;
        } else {
            String newMagnet;
            try {
                StringBuffer sb = new StringBuffer(url);
                int htmlIndex = url.lastIndexOf(".html");
                if (htmlIndex != -1) {
                    sb.delete(htmlIndex, sb.length());
                }
                int paramIndex = url.indexOf("&");
                if (paramIndex != -1) {
                    sb.delete(paramIndex, sb.length());
                }
                if (sb.length() >= 40) {
                    newMagnet = sb.substring(sb.length() - 40, sb.length());
                } else {
                    newMagnet = url;
                }
            } catch (Exception e) {
                e.printStackTrace();
                newMagnet = url;
            }
            return String.format("magnet:?xt=urn:btih:%s", newMagnet);
        }
    }


    /**
     * 从名称里提取清晰度
     *
     * @param name
     * @return
     */
    private String transformResolution(String name) {
        String lowerName = name.toLowerCase();
        String regex4k = ".*(2160|4k).*";
        String regex720 = ".*(1280|720p|720P).*";
        String regex1080 = ".*(1920|1080p|1080P).*";
        boolean matches720 = Pattern.matches(regex720, lowerName);
        if (matches720) {
            return "720P";
        }
        boolean matches1080 = Pattern.matches(regex1080, lowerName);
        if (matches1080) {
            return "1080P";
        }
        boolean matches4k = Pattern.matches(regex4k, lowerName);
        if (matches4k) {
            return "4K";
        }
        return "";
    }


    /**
     * 将文件大小解析成数字
     *
     * @param formatSize
     * @return
     */
    private long transformSize(String formatSize) {
        try {
            long baseNumber = 0;
            if (formatSize.contains("G")) {
                baseNumber = 1024 * 1024 * 1024;
            } else if (formatSize.contains("M")) {
                baseNumber = 1024 * 1024;
            } else if (formatSize.contains("K")) {
                baseNumber = 1024;
            }
            Matcher matcher = Pattern.compile("(\\d+(\\.\\d+)?)").matcher(formatSize);
            if (matcher.find()) {
                String newFormatSize = matcher.group();
                float size = Float.parseFloat(newFormatSize);
                return (long) (size * baseNumber);
            }
        } catch (NumberFormatException e) {
        }
        return 0L;
    }


}
