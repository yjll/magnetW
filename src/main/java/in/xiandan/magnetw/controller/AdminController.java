package in.xiandan.magnetw.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.lang.reflect.Field;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import in.xiandan.magnetw.config.ApplicationConfig;
import in.xiandan.magnetw.handler.PermissionHandler;
import in.xiandan.magnetw.response.BaseResponse;
import in.xiandan.magnetw.response.MagnetPageConfig;
import in.xiandan.magnetw.response.ReportData;
import in.xiandan.magnetw.response.ReportItem;
import in.xiandan.magnetw.service.MagnetRuleService;
import in.xiandan.magnetw.service.MagnetService;
import in.xiandan.magnetw.service.PermissionService;
import in.xiandan.magnetw.service.ReportService;

/**
 * created 2019/5/24 17:13
 */
@Controller
@RequestMapping("admin")
public class AdminController {
    @Autowired
    PermissionService permissionService;

    @Autowired
    MagnetRuleService ruleService;

    @Autowired
    MagnetService magnetService;

    @Autowired
    ReportService reportService;

    @Autowired
    ApplicationConfig config;

    Gson gson = new Gson();

    @RequestMapping(method = RequestMethod.GET)
    public String index(HttpServletResponse response, Model model, @RequestParam(value = "p") String password) throws Exception {
        BaseResponse permission = permissionService.runAsPermission(password, null, null);
        if (permission.isSuccess()) {
            model.addAttribute("config", gson.toJson(new MagnetPageConfig(config)));
            return "admin";
        } else {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write(permission.getMessage());
            return null;
        }
    }

    @ResponseBody
    @RequestMapping(value = "config")
    public BaseResponse config(@RequestParam(value = "p") String password) throws Exception {
        return permissionService.runAsPermission(password, null, new PermissionHandler() {
            @Override
            public Object onPermissionGranted() throws Exception {
                JsonObject newConfig = new JsonObject();
                Field[] fields = ApplicationConfig.class.getFields();
                for (Field field : fields) {
                    Object value = ApplicationConfig.class.getField(field.getName()).get(config);
                    newConfig.addProperty(field.getName(), value.toString());
                }
                return newConfig;
            }
        });
    }

    /**
     * 重载配置
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "reload", method = RequestMethod.GET)
    public BaseResponse reload(@RequestParam(value = "p") String password) throws Exception {
        return permissionService.runAsPermission(password, "规则重载成功", new PermissionHandler<Void>() {
            @Override
            public Void onPermissionGranted() {
                ruleService.reload();
                return null;
            }
        });
    }

    /**
     * 清除缓存
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "clear-cache", method = RequestMethod.GET)
    public BaseResponse clearCache(@RequestParam(value = "p") String password) throws Exception {
        return permissionService.runAsPermission(password, "缓存清除成功", new PermissionHandler<Void>() {
            @Override
            public Void onPermissionGranted() {
                magnetService.clearCache();
                return null;
            }
        });
    }

    /**
     * 重载举报列表
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "report-reload", method = RequestMethod.GET)
    public BaseResponse reportReload(@RequestParam(value = "p") String password) throws Exception {
        BaseResponse permission = permissionService.runAsPermission(password, "举报列表重载成功", null);
        if (permission.isSuccess()) {
            reportService.reload();
        }
        return permission;
    }

    /**
     * 删除举报记录
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "report-delete", method = RequestMethod.DELETE)
    public BaseResponse reportDelete(@RequestParam(value = "p") String password, @RequestParam final String value) throws Exception {
        BaseResponse response = permissionService.runAsPermission(password, "删除成功", null);
        if (response.isSuccess()) {
            reportService.deleteReport(value);
        }
        return response;
    }

    /**
     * 添加举报记录
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "report-add", method = RequestMethod.POST)
    public BaseResponse reportAdd(@RequestParam(value = "p") String password, @RequestParam final String value) throws Exception {
        BaseResponse response = permissionService.runAsPermission(password, "添加成功", null);
        if (response.isSuccess()) {
            reportService.put(null, value);
        }
        return response;
    }


    /**
     * 获取举报列表
     *
     * @param password
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "report-list", method = RequestMethod.GET)
    public BaseResponse reportList(@RequestParam(value = "p") String password) throws Exception {
        BaseResponse response = permissionService.runAsPermission(password, null, null);
        if (response.isSuccess()) {
            List<ReportItem> keywords = reportService.getKeywordList();
            List<ReportItem> urls = reportService.getUrlList();
            ReportData data = new ReportData();
            data.setKeywords(keywords);
            data.setUrls(urls);
            int count = keywords.size() + urls.size();
            response.setMessage(String.format("共%d条记录", count));
            response.setData(data);
        }
        return response;
    }

}
