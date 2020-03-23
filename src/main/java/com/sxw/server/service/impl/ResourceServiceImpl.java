package com.sxw.server.service.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.sxw.printer.Printer;
import com.sxw.server.enumeration.AccountAuth;
import com.sxw.server.enumeration.PowerPointType;
import com.sxw.server.mapper.PropertiesMapper;
import com.sxw.server.model.Propertie;
import com.sxw.server.pojo.ResponseBodyDTO;
import com.sxw.server.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.sxw.server.mapper.FolderMapper;
import com.sxw.server.mapper.NodeMapper;
import com.sxw.server.model.Node;
import com.sxw.server.pojo.VideoTranscodeThread;
import com.sxw.server.service.ResourceService;

//资源服务类，所有处理非下载流请求的工作均在此完成
@Service
public class ResourceServiceImpl implements ResourceService {

    @Resource
    private NodeMapper nm;
    @Resource
    private FileBlockUtil fbu;
    @Resource
    private LogUtil lu;
    @Resource
    private Docx2PDFUtil d2pu;
    @Resource
    private Txt2PDFUtil t2pu;
    @Resource
    private VideoTranscodeUtil vtu;
    @Resource
    private PowerPoint2PDFUtil p2pu;
    @Resource
    private FolderUtil fu;
    @Resource
    private FolderMapper fm;
    @Resource
    private TxtCharsetGetter tcg;
    @Resource
    private NoticeUtil nu;
    @Resource
    private ContentTypeMap ctm;
    @Resource
    private SxwFFMPEGLocator kfl;
    @Resource
    private SxwApiUtil sau;
    @Resource
    private Gson gson;
    @Resource
    private AESCipher cipher;
    @Resource
    private PropertiesMapper pm;

    // 提供资源的输出流，原理与下载相同，但是个别细节有区别
    @Override
    public void getResource(HttpServletRequest request, HttpServletResponse response) {
        // TODO 自动生成的方法存根
        final String account = (String) request.getSession().getAttribute("ACCOUNT");
        String fid = request.getParameter("fid");
        if (fid != null) {
            Node n = nm.queryById(fid);
            if (n != null) {
                if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
                        fu.getAllFoldersId(n.getFileParentFolder()))
                        && ConfigureReader.instance().accessFolder(fm.queryById(n.getFileParentFolder()), account)) {
                    File file = fbu.getFileFromBlocks(n);
                    if (file != null && file.isFile()) {
                        String suffix = "";
                        if (n.getFileName().indexOf(".") >= 0) {
                            suffix = n.getFileName().substring(n.getFileName().lastIndexOf(".")).trim().toLowerCase();
                        }
                        String contentType = ctm.getContentType(suffix);
                        switch (suffix) {
                            case ".mp4":
                            case ".webm":
                            case ".mov":
                            case ".avi":
                            case ".wmv":
                            case ".mkv":
                            case ".flv":
                                if (kfl.getFFMPEGExecutablePath() != null) {
                                    contentType = "video/mp4";
                                    synchronized (VideoTranscodeUtil.videoTranscodeThreads) {
                                        VideoTranscodeThread vtt = VideoTranscodeUtil.videoTranscodeThreads.get(fid);
                                        if (vtt != null) {// 针对需要转码的视频（在转码列表中存在）
                                            File f = new File(ConfigureReader.instance().getTemporaryfilePath(),
                                                    vtt.getOutputFileName());
                                            if (f.isFile() && vtt.getProgress().equals("FIN")) {// 判断是否转码成功
                                                file = f;// 成功，则播放它
                                            } else {
                                                try {
                                                    response.sendError(500);// 否则，返回处理失败
                                                } catch (IOException e) {
                                                }
                                                return;
                                            }
                                        }
                                    }
                                }
                                break;
                            default:
                                break;
                        }
                        sendResource(file, n.getFileName(), contentType, request, response);
                        if (request.getHeader("Range") == null) {
                            this.lu.writeDownloadFileEvent(request, n);
                        }
                        return;
                    }
                }
            }
        }
        try {
            //  处理无法下载的资源
            response.sendError(404);
        } catch (IOException e) {
            // TODO 自动生成的 catch 块
        }
    }

    // 使用各个浏览器（主要是Safari）兼容的通用格式发送资源至请求来源，类似于断点续传下载功能
    private void sendResource(File resource, String fname, String contentType, HttpServletRequest request,
                              HttpServletResponse response) {
        try (RandomAccessFile randomFile = new RandomAccessFile(resource, "r")) {
            long contentLength = randomFile.length();
            String range = request.getHeader("Range");
            long start = 0, end = 0;
            if (range != null && range.startsWith("bytes=")) {
                String[] values = range.split("=")[1].split("-");
                start = Long.parseLong(values[0]);
                if (values.length > 1) {
                    end = Long.parseLong(values[1]);
                }
            }
            long requestSize = 0;
            if (end != 0 && end > start) {
                requestSize = end - start + 1;
            } else {
                requestSize = Long.MAX_VALUE;
            }
            byte[] buffer = new byte[ConfigureReader.instance().getBuffSize()];
            response.setContentType(contentType);
            response.setHeader("Accept-Ranges", "bytes");
            String lastModified = resource.lastModified() + "";
            response.setHeader("ETag", lastModified);
            response.setHeader("Last-Modified", lastModified);
            // 第一次请求只返回content length来让客户端请求多次实际数据
            if (range == null) {
                response.setHeader("Content-length", contentLength + "");
            } else {
                // 以后的多次以断点续传的方式来返回视频数据
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);// 206
                long requestStart = 0, requestEnd = 0;
                String[] ranges = range.split("=");
                if (ranges.length > 1) {
                    String[] rangeDatas = ranges[1].split("-");
                    requestStart = Long.parseLong(rangeDatas[0]);
                    if (rangeDatas.length > 1) {
                        requestEnd = Long.parseLong(rangeDatas[1]);
                    }
                }
                long length = 0;
                if (requestEnd > 0) {
                    length = requestEnd - requestStart + 1;
                    response.setHeader("Content-length", "" + length);
                    response.setHeader("Content-Range",
                            "bytes " + requestStart + "-" + requestEnd + "/" + contentLength);
                } else {
                    length = contentLength - requestStart;
                    response.setHeader("Content-length", "" + length);
                    response.setHeader("Content-Range",
                            "bytes " + requestStart + "-" + (contentLength - 1) + "/" + contentLength);
                }
            }
            ServletOutputStream out = response.getOutputStream();
            long needSize = requestSize;
            randomFile.seek(start);
            while (needSize > 0) {
                int len = randomFile.read(buffer);
                if (needSize < buffer.length) {
                    out.write(buffer, 0, (int) needSize);
                } else {
                    out.write(buffer, 0, len);
                    if (len < buffer.length) {
                        break;
                    }
                }
                needSize -= buffer.length;
            }
            out.close();
        } catch (Exception e) {

        }
    }

    // 对word的预览实现
    @Override
    public void getWordView(String fileId, HttpServletRequest request, HttpServletResponse response) {
        final String account = (String) request.getSession().getAttribute("ACCOUNT");
        // 权限检查
        if (fileId != null) {
            Node n = nm.queryById(fileId);
            if (n != null) {
                if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
                        fu.getAllFoldersId(n.getFileParentFolder()))
                        && ConfigureReader.instance().accessFolder(fm.queryById(n.getFileParentFolder()), account)) {
                    File file = fbu.getFileFromBlocks(n);
                    if (file != null && file.isFile()) {
                        // 后缀检查
                        String suffix = "";
                        if (n.getFileName().indexOf(".") >= 0) {
                            suffix = n.getFileName().substring(n.getFileName().lastIndexOf(".")).trim().toLowerCase();
                        }
                        if (".docx".equals(suffix)) {
                            String contentType = "application/octet-stream";
                            response.setContentType(contentType);
                            // 执行转换并写出输出流
                            try {
                                d2pu.convertPdf(new FileInputStream(file), response.getOutputStream());
                                lu.writeDownloadFileEvent(request, n);
                                return;
                            } catch (IOException e) {
                            } catch (Exception e) {
                                Printer.instance.print(e.getMessage());
                                lu.writeException(e);
                            }
                        }
                    }
                }
            }
        }
        try {
            response.sendError(500);
        } catch (Exception e1) {
        }
    }

    // 对TXT预览的实现
    @Override
    public void getTxtView(String fileId, HttpServletRequest request, HttpServletResponse response) {
        final String account = (String) request.getSession().getAttribute("ACCOUNT");
        // 权限检查
        if (fileId != null) {
            Node n = nm.queryById(fileId);
            if (n != null) {
                if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
                        fu.getAllFoldersId(n.getFileParentFolder()))
                        && ConfigureReader.instance().accessFolder(fm.queryById(n.getFileParentFolder()), account)) {
                    File file = fbu.getFileFromBlocks(n);
                    if (file != null && file.isFile()) {
                        // 后缀检查
                        String suffix = "";
                        if (n.getFileName().indexOf(".") >= 0) {
                            suffix = n.getFileName().substring(n.getFileName().lastIndexOf(".")).trim().toLowerCase();
                        }
                        // ".txt".equals(suffix)
                        if (true) {
                            String contentType = "application/octet-stream";
                            response.setContentType(contentType);
                            // 执行转换并写出输出流
                            try {
                                t2pu.convertPdf(file, response.getOutputStream());
                                lu.writeDownloadFileEvent(request, n);
                                return;
                            } catch (Exception e) {
                                Printer.instance.print(e.getMessage());
                                lu.writeException(e);
                            }
                        }
                    }
                }
            }
        }
        try {
            response.sendError(500);
        } catch (Exception e1) {
        }
    }

    @Override
    public String getVideoTranscodeStatus(HttpServletRequest request) {
        if (kfl.getFFMPEGExecutablePath() != null) {
            String fId = request.getParameter("fileId");
            if (fId != null) {
                try {
                    return vtu.getTranscodeProcess(fId);
                } catch (Exception e) {
                    Printer.instance.print("错误：视频转码功能出现意外错误。详细信息：" + e.getMessage());
                    lu.writeException(e);
                }
            }
        }
        return "ERROR";
    }

    // 对PPT预览的实现
    @Override
    public void getPPTView(String fileId, HttpServletRequest request, HttpServletResponse response) {
        final String account = (String) request.getSession().getAttribute("ACCOUNT");
        // 权限检查
        if (fileId != null) {
            Node n = nm.queryById(fileId);
            if (n != null) {
                if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
                        fu.getAllFoldersId(n.getFileParentFolder()))
                        && ConfigureReader.instance().accessFolder(fm.queryById(n.getFileParentFolder()), account)) {
                    File file = fbu.getFileFromBlocks(n);
                    if (file != null && file.isFile()) {
                        // 后缀检查
                        String suffix = "";
                        if (n.getFileName().indexOf(".") >= 0) {
                            suffix = n.getFileName().substring(n.getFileName().lastIndexOf(".")).trim().toLowerCase();
                        }
                        switch (suffix) {
                            case ".ppt":
                            case ".pptx":
                                String contentType = "application/octet-stream";
                                response.setContentType(contentType);
                                // 执行转换并写出输出流
                                try {
                                    p2pu.convertPdf(new FileInputStream(file), response.getOutputStream(),
                                            ".ppt".equals(suffix) ? PowerPointType.PPT : PowerPointType.PPTX);
                                    lu.writeDownloadFileEvent(request, n);
                                    return;
                                } catch (IOException e) {
                                } catch (Exception e) {
                                    Printer.instance.print(e.getMessage());
                                    lu.writeException(e);
                                }
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
        }
        try {
            response.sendError(500);
        } catch (Exception e1) {
        }
    }

    @Override
    public void getLRContextByUTF8(String fileId, HttpServletRequest request, HttpServletResponse response) {
        final String account = (String) request.getSession().getAttribute("ACCOUNT");
        // 权限检查
        if (fileId != null) {
            Node n = nm.queryById(fileId);
            if (n != null) {
                if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
                        fu.getAllFoldersId(n.getFileParentFolder()))
                        && ConfigureReader.instance().accessFolder(fm.queryById(n.getFileParentFolder()), account)) {
                    File file = fbu.getFileFromBlocks(n);
                    if (file != null && file.isFile()) {
                        // 后缀检查
                        String suffix = "";
                        if (n.getFileName().indexOf(".") >= 0) {
                            suffix = n.getFileName().substring(n.getFileName().lastIndexOf(".")).trim().toLowerCase();
                        }
                        if (".lrc".equals(suffix)) {
                            String contentType = "text/plain";
                            response.setContentType(contentType);
                            // 执行转换并写出输出流
                            try {
                                String inputFileEncode = tcg.getTxtCharset(new FileInputStream(file));
                                BufferedReader bufferedReader = new BufferedReader(
                                        new InputStreamReader(new FileInputStream(file), inputFileEncode));
                                BufferedWriter bufferedWriter = new BufferedWriter(
                                        new OutputStreamWriter(response.getOutputStream(), "UTF-8"));
                                String line;
                                while ((line = bufferedReader.readLine()) != null) {
                                    bufferedWriter.write(line);
                                    bufferedWriter.newLine();
                                }
                                bufferedWriter.close();
                                bufferedReader.close();
                                return;
                            } catch (IOException e) {
                            } catch (Exception e) {
                                Printer.instance.print(e.getMessage());
                                lu.writeException(e);
                            }

                        }
                    }
                }
            }
        }
        try {
            response.sendError(500);
        } catch (Exception e1) {
        }
    }

    @Override
    public void getNoticeContext(HttpServletRequest request, HttpServletResponse response) {
        File noticeHTML = new File(ConfigureReader.instance().getTemporaryfilePath(), NoticeUtil.NOTICE_OUTPUT_NAME);
        String contentType = "text/html";
        if (noticeHTML.isFile() && noticeHTML.canRead()) {
            sendResource(noticeHTML, NoticeUtil.NOTICE_FILE_NAME, contentType, request, response);
        } else {
            try {
                response.setContentType(contentType);
                response.setCharacterEncoding("UTF-8");
                PrintWriter writer = response.getWriter();
                writer.write("<p class=\"lead\">暂无新公告。</p>");
                writer.flush();
                writer.close();
            } catch (IOException e) {
            }
        }
    }

    @Override
    public String getNoticeMD5() {
        return nu.getMd5();
    }

    @Override
    public String getFilePreViewUrl(HttpServletRequest request, HttpServletResponse response) {
        final String account = (String) request.getSession().getAttribute("ACCOUNT");
        if (account == null) {
            return "NoAuth";
        }
        final String resourceUrl = request.getParameter("resourceUrl");
        return sau.getFilePreViewUrl(resourceUrl);
    }

    @Override
    public String getH5FilePreViewUrl(String fileId, HttpServletRequest request) {
        final String account = (String) request.getSession().getAttribute("ACCOUNT");

        ResponseBodyDTO responseBodyDTO = new ResponseBodyDTO();
        if (account == null) {
            responseBodyDTO.setData("Token is lose efficacy!");
            responseBodyDTO.setMessage("登陆失效");
            responseBodyDTO.setCode(HttpStatus.FORBIDDEN.value());
            return gson.toJson(responseBodyDTO);
        }

        String ckey = null;
        if (fileId != null) {
            final Node f = this.nm.queryById(fileId);
            if (f != null) {

                // 将指定的fid加密为ckey并返回。
                try {
                    Propertie keyProp = pm.selectByKey("chain_aes_key");
                    if (keyProp == null) {// 如果没有生成过永久性AES密钥，则先生成再加密
                        String aesKey = cipher.generateRandomKey();
                        Propertie chainAESKey = new Propertie();
                        chainAESKey.setPropertieKey("chain_aes_key");
                        chainAESKey.setPropertieValue(aesKey);
                        if (pm.insert(chainAESKey) > 0) {
                            ckey = cipher.encrypt(aesKey, fileId);
                        }
                    } else {// 如果已经有了，则直接用其加密
                        ckey = cipher.encrypt(keyProp.getPropertieValue(), fileId);
                    }

                    Node node = nm.queryById(fileId);
                    if (node == null || node.getFilePath() == null || ckey == null) {
                        responseBodyDTO.setData("no such file!");
                        responseBodyDTO.setMessage("没有该文件");
                        responseBodyDTO.setCode(HttpStatus.BAD_REQUEST.value());
                        return gson.toJson(responseBodyDTO);
                    }
                    String resourceUrl = ConfigureReader.instance().getlocalFileResourceUrl() + URLEncoder.encode(node.getFileName(), "utf-8") + "?ckey=" + URLEncoder.encode(ckey, "utf-8");
                    responseBodyDTO.setData(sau.getFilePreViewUrl(resourceUrl));
                    responseBodyDTO.setMessage("请求成功！");
                    responseBodyDTO.setCode(HttpStatus.OK.value());
                    return gson.toJson(responseBodyDTO);
                } catch (Exception e) {
                    lu.writeException(e);
                    responseBodyDTO.setData(e.getMessage());
                    responseBodyDTO.setMessage("内部错误！");
                    responseBodyDTO.setCode(HttpStatus.INTERNAL_SERVER_ERROR.value());
                    return gson.toJson(responseBodyDTO);
                }
            }
        }
        responseBodyDTO.setData("parameterError");
        responseBodyDTO.setMessage("传入参数为空！");
        responseBodyDTO.setCode(HttpStatus.BAD_REQUEST.value());
        return gson.toJson(responseBodyDTO);
    }


}
