package com.icecream;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AttendanceSystem {
    private static final String ACCOUNTS_FILE = "student_accounts.json";
    // Can be set for debugging or specific date testing
    private static final String ARG_DATE = null; // Format YYYYMMDD
    
    // Student account class to store user information and state
    private static class StudentAccount {
        String studentNumber;
        String password;
        String userId = "";
        String sessionId = "";
        boolean isLoggedIn = false;
        long lastLoginTime = 0;
        final Set<String> signedCourses = new HashSet<>();
        final Map<String, JSONObject> todayCourses = new HashMap<>();
        LocalDate lastCoursesLoadDate = null; // Track the date when courses were last loaded
        
        public StudentAccount(String studentNumber, String password) {
            this.studentNumber = studentNumber;
            this.password = password;
        }
    }
    
    // For tracking session refresh interval
    private static final long SESSION_REFRESH_INTERVAL = 30 * 60 * 1000; // 30 minutes in milliseconds
    
    // Map to store all student accounts
    private static final Map<String, StudentAccount> studentAccounts = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        try {
            System.out.println("多账户自动考勤系统启动...");
            System.out.println("系统将每分钟检查一次是否有课程需要打卡。");
            
            // Load all accounts from the config file
            loadAccounts();
            
            // Check if we have any accounts, if not, prompt to add one
            if (studentAccounts.isEmpty()) {
                addNewAccount();
            }
            
            // Schedule the attendance check to run every minute
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    System.out.println("\n[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "] 开始检查所有账户...");
                    
                    // Process each account
                    for (Map.Entry<String, StudentAccount> entry : studentAccounts.entrySet()) {
                        String studentNumber = entry.getKey();
                        StudentAccount account = entry.getValue();
                        
                        System.out.println("\n处理账户: " + maskStudentNumber(studentNumber));
                        
                        // Check if session needs refreshing
                        boolean needsRefresh = !account.isLoggedIn || (System.currentTimeMillis() - account.lastLoginTime > SESSION_REFRESH_INTERVAL);
                        
                        if (needsRefresh) {
                            System.out.println("会话过期或需要刷新，正在重新登录...");
                            account.isLoggedIn = false;
                            login(account);
                        } else {
                            System.out.println("会话有效，检查课程...");
                        }
                        
                        // If login successful, check courses and sign in
                        if (account.isLoggedIn) {
                            checkAndSignIn(account);
                        }
                    }
                } catch (Exception e) {
                    System.out.println("执行自动考勤过程中发生错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, 1, TimeUnit.MINUTES);
            
            // Start a management thread to handle user commands
            Thread managementThread = new Thread(() -> manageAccounts());
            managementThread.setDaemon(true);
            managementThread.start();
            
            // Keep the application running
            System.out.println("系统正在运行中");
            System.out.println("输入 'help' 查看可用命令，或按 Ctrl+C 退出程序");
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.out.println("程序出现异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * User interface for account management
     */
    private static void manageAccounts() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            try {
                String command = scanner.nextLine().trim();
                
                switch (command.toLowerCase()) {
                    case "help":
                        System.out.println("\n可用命令:");
                        System.out.println("list - 列出所有账户");
                        System.out.println("add - 添加新账户");
                        System.out.println("remove - 删除账户");
                        System.out.println("check - 手动检查所有课程");
                        System.out.println("exit - 退出程序");
                        System.out.println("help - 显示此帮助信息");
                        break;
                        
                    case "list":
                        listAccounts();
                        break;
                        
                    case "add":
                        addNewAccount();
                        break;
                        
                    case "remove":
                        removeAccount();
                        break;
                        
                    case "check":
                        manualCheck();
                        break;
                        
                    case "exit":
                        System.out.println("正在退出系统...");
                        System.exit(0);
                        break;
                        
                    default:
                        if (!command.isEmpty()) {
                            System.out.println("未知命令，输入 'help' 查看可用命令");
                        }
                        break;
                }
            } catch (Exception e) {
                System.out.println("处理命令时出错: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * List all registered accounts
     */
    private static void listAccounts() {
        if (studentAccounts.isEmpty()) {
            System.out.println("没有注册的账户");
            return;
        }
        
        System.out.println("\n当前注册的账户:");
        int index = 1;
        for (String studentNumber : studentAccounts.keySet()) {
            StudentAccount account = studentAccounts.get(studentNumber);
            System.out.println(index + ". " + maskStudentNumber(studentNumber) + 
                    " (状态: " + (account.isLoggedIn ? "已登录" : "未登录") + ")");
            index++;
        }
    }
    
    /**
     * Add a new student account
     */
    private static void addNewAccount() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("添加新账户");
        System.out.println("-----------");
        
        String studentNumber;
        while (true) {
            System.out.println("请输入学号:");
            studentNumber = scanner.nextLine().trim();
            
            if (studentNumber.isEmpty()) {
                System.out.println("学号不能为空，请重新输入");
                continue;
            }
            
            if (studentAccounts.containsKey(studentNumber)) {
                System.out.println("该学号已存在，请使用其他学号");
                continue;
            }
            
            break;
        }
        
        System.out.println("请输入密码:");
        String password = scanner.nextLine().trim();
        
        if (password.isEmpty()) {
            System.out.println("添加账户失败：密码不能为空");
            return;
        }
        
        // Create and add the new account
        StudentAccount newAccount = new StudentAccount(studentNumber, password);
        studentAccounts.put(studentNumber, newAccount);
        
        // Save all accounts
        saveAccounts();
        
        System.out.println("账户添加成功：" + maskStudentNumber(studentNumber));
        
        // Attempt to login with the new account
        login(newAccount);
    }
    
    /**
     * Remove an existing student account
     */
    private static void removeAccount() {
        if (studentAccounts.isEmpty()) {
            System.out.println("没有注册的账户可删除");
            return;
        }
        
        Scanner scanner = new Scanner(System.in);
        
        // List all accounts first
        listAccounts();
        
        System.out.println("\n请输入要删除的账户学号:");
        String studentNumber = scanner.nextLine().trim();
        
        if (!studentAccounts.containsKey(studentNumber)) {
            System.out.println("找不到该学号的账户");
            return;
        }
        
        System.out.println("确认删除账户 " + maskStudentNumber(studentNumber) + "? (y/n)");
        String confirm = scanner.nextLine().trim().toLowerCase();
        
        if (confirm.equals("y") || confirm.equals("yes")) {
            studentAccounts.remove(studentNumber);
            saveAccounts();
            System.out.println("账户已删除");
        } else {
            System.out.println("取消删除操作");
        }
    }
    
    /**
     * Manually trigger a check for all accounts
     */
    private static void manualCheck() {
        if (studentAccounts.isEmpty()) {
            System.out.println("没有注册的账户");
            return;
        }
        
        System.out.println("手动检查所有账户的课程...");
        
        for (Map.Entry<String, StudentAccount> entry : studentAccounts.entrySet()) {
            String studentNumber = entry.getKey();
            StudentAccount account = entry.getValue();
            
            System.out.println("\n检查账户: " + maskStudentNumber(studentNumber));
            
            if (!account.isLoggedIn) {
                System.out.println("账户未登录，尝试登录...");
                login(account);
            }
            
            if (account.isLoggedIn) {
                // Force refresh courses
                account.todayCourses.clear();
                loadTodayCourses(account);
                checkAndSignIn(account);
            }
        }
        
        System.out.println("\n手动检查完成");
    }
    
    /**
     * Mask student number for privacy
     */
    private static String maskStudentNumber(String studentNumber) {
        if (studentNumber.length() <= 4) {
            return studentNumber;
        }
        return studentNumber.substring(0, 2) + "****" + studentNumber.substring(studentNumber.length() - 2);
    }
    
    /**
     * Handle the login process for an account
     */
    private static void login(StudentAccount account) {
        try {
            System.out.println("正在登录系统... 用户: " + maskStudentNumber(account.studentNumber));

            // Initialize HTTP client and cookie store
            CookieStore cookieStore = new BasicCookieStore();
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultCookieStore(cookieStore)
                    .build();
            HttpClientContext context = HttpClientContext.create();
            context.setCookieStore(cookieStore);

            // First request to get cookies and execution parameter
            URI uriLogin = new URIBuilder("https://sso.buaa.edu.cn/login")
                    .addParameter("service", "https://iclass.buaa.edu.cn:8346/")
                    .build();
            HttpGet loginGet = new HttpGet(uriLogin);
            
            String responseBody;
            String cookieIp = "";
            String executionValue = "";
            
            try (CloseableHttpResponse response = httpClient.execute(loginGet, context)) {
                HttpEntity entity = response.getEntity();
                responseBody = EntityUtils.toString(entity);
                
                // Extract cookie IP
                Pattern patternIp = Pattern.compile("http://\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+");
                Matcher matcherIp = patternIp.matcher(cookieStore.getCookies().toString());
                if (matcherIp.find()) {
                    cookieIp = matcherIp.group();
                } else {
                    System.out.println("Extract cookie IP ERROR");
                    return;
                }
                
                // Extract execution value
                Pattern patternEx = Pattern.compile("<input name=\"execution\" value=\"([^\"]+)\"/>");
                Matcher matcherEx = patternEx.matcher(responseBody);
                if (matcherEx.find()) {
                    executionValue = matcherEx.group(1);
                } else {
                    System.out.println("Extract execution value ERROR");
                    return;
                }
            }
            
            // Add the cookie
            BasicClientCookie cookie = new BasicClientCookie("_7da9a", cookieIp);
            cookie.setDomain("sso.buaa.edu.cn");
            cookie.setPath("/");
            cookieStore.addCookie(cookie);
            
            // Post login data
            HttpPost loginPost = new HttpPost("https://sso.buaa.edu.cn/login");
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("username", account.studentNumber));
            params.add(new BasicNameValuePair("password", account.password));
            params.add(new BasicNameValuePair("submit", "登录"));
            params.add(new BasicNameValuePair("type", "username_password"));
            params.add(new BasicNameValuePair("execution", executionValue));
            params.add(new BasicNameValuePair("_eventId", "submit"));
            loginPost.setEntity(new UrlEncodedFormEntity(params));
            
            String phone = "";
            
            // Don't use automatic redirects for this request so we can properly track the redirect chain
            CloseableHttpClient noRedirectClient = HttpClients.custom()
                    .setDefaultCookieStore(cookieStore)
                    .disableRedirectHandling()
                    .build();
            
            try {
                HttpResponse response = noRedirectClient.execute(loginPost, context);
                int statusCode = response.getStatusLine().getStatusCode();
                
                // Follow redirects manually to track the entire chain
                String location = "";
                if (statusCode >= 300 && statusCode < 400) {
                    location = response.getFirstHeader("Location").getValue();
                    
                    // Follow redirects until we find the URL with loginName
                    int maxRedirects = 10;
                    while (maxRedirects > 0 && !location.contains("loginName=")) {
                        HttpGet redirectGet = new HttpGet(location);
                        response = noRedirectClient.execute(redirectGet, context);
                        statusCode = response.getStatusLine().getStatusCode();
                        
                        if (statusCode >= 300 && statusCode < 400 && response.getFirstHeader("Location") != null) {
                            location = response.getFirstHeader("Location").getValue();
                        } else {
                            break;
                        }
                        maxRedirects--;
                    }
                    
                    // Now try to extract the phone parameter
                    Pattern patternPhone = Pattern.compile("loginName=([A-F0-9]+)");
                    Matcher matcherPhone = patternPhone.matcher(location);
                    if (matcherPhone.find()) {
                        phone = matcherPhone.group(1);
                        System.out.println("登录成功");
                    } else {
                        System.out.println("Error: Could not find loginName in redirect URL");
                        return;
                    }
                } else {
                    // If no redirect, something went wrong
                    HttpEntity entity = response.getEntity();
                    String newResponseBody = EntityUtils.toString(entity);
                    System.out.println("Login failed. Status code: " + statusCode);
                    
                    // Check if the response contains error messages
                    if (newResponseBody.contains("用户名或密码错误")) {
                        System.out.println("用户名或密码错误，请检查后重试");
                    } else {
                        System.out.println("登录失败，请检查网络连接或稍后再试");
                    }
                    return;
                }
            } catch (Exception e) {
                System.out.println("Exception during login process: " + e.getMessage());
                e.printStackTrace();
                return;
            }
            
            // Get user ID and session ID for class schedule
            URI userLoginUri = new URIBuilder("https://iclass.buaa.edu.cn:8346/app/user/login_buaa.action")
                    .addParameter("password", "")
                    .addParameter("phone", phone)
                    .addParameter("userLevel", "1")
                    .addParameter("verificationType", "2")
                    .addParameter("verificationUrl", "")
                    .build();
                    
            HttpGet userLoginGet = new HttpGet(userLoginUri);
            
            try (CloseableHttpResponse response = httpClient.execute(userLoginGet)) {
                HttpEntity entity = response.getEntity();
                String userDataStr = EntityUtils.toString(entity);
                JSONObject userData = new JSONObject(userDataStr);
                account.userId = userData.getJSONObject("result").getString("id");
                account.sessionId = userData.getJSONObject("result").getString("sessionId");
                account.isLoggedIn = true;
                account.lastLoginTime = System.currentTimeMillis();
                System.out.println("获取用户信息成功，准备检查今日课程");
                
                // Load today's courses once logged in
                loadTodayCourses(account);
            }
            
            httpClient.close();
            
        } catch (Exception e) {
            System.out.println("登录过程中发生错误: " + e.getMessage());
            e.printStackTrace();
            account.isLoggedIn = false;
        }
    }
    
    /**
     * Load all courses for today for the given account
     */
    private static void loadTodayCourses(StudentAccount account) {
        try {
            CloseableHttpClient httpClient = HttpClients.custom().build();
            
            // Date handling
            Date today;
            if (ARG_DATE != null) {
                try {
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
                    today = dateFormat.parse(ARG_DATE);
                } catch (Exception e) {
                    System.out.println("日期格式错误，请使用YYYYMMDD格式（如：20250304）");
                    return;
                }
            } else {
                today = new Date(); // Today's date
            }
            
            // Calculate date for current day
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(today);
            
            SimpleDateFormat dateStrFormat = new SimpleDateFormat("yyyyMMdd");
            String dateStr = dateStrFormat.format(calendar.getTime());
            
            // Update the lastCoursesLoadDate to track when we last loaded courses
            account.lastCoursesLoadDate = LocalDate.now();
            
            // Query course schedule
            URI courseUri = new URIBuilder("https://iclass.buaa.edu.cn:8346/app/course/get_stu_course_sched.action")
                    .addParameter("dateStr", dateStr)
                    .addParameter("id", account.userId)
                    .build();
                    
            HttpGet courseGet = new HttpGet(courseUri);
            courseGet.setHeader("sessionId", account.sessionId);
            
            try (CloseableHttpResponse response = httpClient.execute(courseGet)) {
                HttpEntity entity = response.getEntity();
                String jsonResponse = EntityUtils.toString(entity);
                JSONObject jsonData = new JSONObject(jsonResponse);
                
                if ("0".equals(jsonData.getString("STATUS"))) {
                    JSONArray courses = jsonData.getJSONArray("result");
                    account.todayCourses.clear(); // Clear previous courses
                    
                    // Reset signed courses when loading a new day's courses
                    account.signedCourses.clear();
                    
                    // Cache today's courses
                    System.out.println("\n账户 " + maskStudentNumber(account.studentNumber) + " 今日课程列表：");
                    for (int idx = 0; idx < courses.length(); idx++) {
                        JSONObject course = courses.getJSONObject(idx);
                        String courseId = course.getString("id");
                        String classBeginTime = course.getString("classBeginTime");
                        String classEndTime = course.getString("classEndTime");
                        String classTime = classBeginTime.substring(11, 16) + "~" + classEndTime.substring(11, 16);
                        
                        account.todayCourses.put(courseId, course);
                        System.out.println((idx + 1) + ". " + course.getString("courseName") + 
                                " (上课时间：" + classBeginTime.substring(0, 10) + " " + classTime + ")");
                    }
                    
                    if (courses.length() == 0) {
                        System.out.println("今天没有课程");
                    }
                } else {
                    System.out.println("获取课程列表失败");
                }
            }
            
            httpClient.close();
            
        } catch (Exception e) {
            System.out.println("加载今日课程时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Check if courses need to be refreshed for a new day
     */
    private static boolean shouldRefreshCourses(StudentAccount account) {
        LocalDate today = LocalDate.now();
        
        // If we've never loaded courses or the date has changed since we last loaded courses
        return account.lastCoursesLoadDate == null || !today.equals(account.lastCoursesLoadDate);
    }
    
    /**
     * Check if any course is in session and sign in for a specific account
     */
    private static void checkAndSignIn(StudentAccount account) {
        try {
            System.out.println("\n[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + 
                    "] 检查账户 " + maskStudentNumber(account.studentNumber) + " 是否有课程需要打卡...");
            
            // Check if we need to refresh courses for a new day
            if (shouldRefreshCourses(account)) {
                System.out.println("检测到新的一天，刷新课程列表...");
                loadTodayCourses(account);
            }
            // If no courses for today, load them
            else if (account.todayCourses.isEmpty()) {
                loadTodayCourses(account);
            }
            
            // For connection to sign in
            CloseableHttpClient httpClient = HttpClients.custom().build();
            
            // Current time
            LocalDateTime currentTime = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            // Check if any course is currently in session
            boolean foundActiveCourse = false;
            
            for (Map.Entry<String, JSONObject> entry : account.todayCourses.entrySet()) {
                String courseSchedId = entry.getKey();
                JSONObject course = entry.getValue();
                
                String classBeginTime = course.getString("classBeginTime");
                String classEndTime = course.getString("classEndTime");
                String courseName = course.getString("courseName");
                
                // Parse course times
                LocalDateTime courseStart = LocalDateTime.parse(classBeginTime, formatter).minusMinutes(10); // Can sign in 10 mins before class
                LocalDateTime courseEnd = LocalDateTime.parse(classEndTime, formatter);
                
                // Check if this course is currently in session and hasn't been signed in yet
                if (currentTime.isAfter(courseStart) && currentTime.isBefore(courseEnd) && !account.signedCourses.contains(courseSchedId)) {
                    foundActiveCourse = true;
                    System.out.println("检测到课程 [" + courseName + "] 正在进行中，尝试打卡...");
                    
                    // Perform sign in
                    long currentTimestamp = System.currentTimeMillis();
                    String url = "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
                            "?courseSchedId=" + courseSchedId + "&timestamp=" + currentTimestamp;
                            
                    URI attendanceUri = new URIBuilder(url)
                            .addParameter("id", account.userId)
                            .build();
                            
                    HttpPost attendancePost = new HttpPost(attendanceUri);
                    
                    try (CloseableHttpResponse response = httpClient.execute(attendancePost)) {
                        if (response.getStatusLine().getStatusCode() == 200) {
                            String responseText = EntityUtils.toString(response.getEntity());
                            JSONObject data = new JSONObject(responseText);
                            if ("1".equals(data.getString("STATUS"))) {
                                System.out.println("疑似未开启扫码签到：" + courseName + 
                                        "。\t上课时间：" + classBeginTime.substring(0, 10) + " " + 
                                        classBeginTime.substring(11, 16) + "~" + classEndTime.substring(11, 16) + "。");
                            } else {
                                System.out.println("✅ 已成功打卡：" + courseName + 
                                        "。\t上课时间：" + classBeginTime.substring(0, 10) + " " + 
                                        classBeginTime.substring(11, 16) + "~" + classEndTime.substring(11, 16) + 
                                        "。\t当前时间：" + currentTime);
                                // Add to the set of signed courses to prevent duplicate sign-ins
                                account.signedCourses.add(courseSchedId);
                            }
                        } else {
                            System.out.println("❌ 打卡失败：" + courseName + 
                                    "。\t上课时间：" + classBeginTime.substring(0, 10) + " " + 
                                    classBeginTime.substring(11, 16) + "~" + classEndTime.substring(11, 16) + "。");
                        }
                    }
                }
            }
            
            if (!foundActiveCourse) {
                System.out.println("当前没有需要打卡的课程");
            }
            
            httpClient.close();
            
        } catch (Exception e) {
            System.out.println("检查和打卡过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Load all accounts from the configuration file
     */
    private static void loadAccounts() {
        File file = new File(ACCOUNTS_FILE);
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, read);
                }
                
                JSONObject data = new JSONObject(content.toString());
                JSONArray accounts = data.getJSONArray("accounts");
                
                studentAccounts.clear();
                
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject accountJson = accounts.getJSONObject(i);
                    String studentNumber = accountJson.getString("student_number");
                    String password = accountJson.getString("password");
                    
                    StudentAccount account = new StudentAccount(studentNumber, password);
                    studentAccounts.put(studentNumber, account);
                }
                
                System.out.println("已加载 " + studentAccounts.size() + " 个账户");
            } catch (Exception e) {
                System.out.println("加载账户信息时出错: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("账户配置文件不存在，将创建新文件");
        }
    }
    
    /**
     * Save all accounts to the configuration file
     */
    private static void saveAccounts() {
        try (FileWriter writer = new FileWriter(ACCOUNTS_FILE)) {
            JSONObject data = new JSONObject();
            JSONArray accounts = new JSONArray();
            
            for (Map.Entry<String, StudentAccount> entry : studentAccounts.entrySet()) {
                StudentAccount account = entry.getValue();
                JSONObject accountJson = new JSONObject();
                accountJson.put("student_number", account.studentNumber);
                accountJson.put("password", account.password);
                accounts.put(accountJson);
            }
            
            data.put("accounts", accounts);
            writer.write(data.toString(2)); // Pretty print with indent of 2
            
        } catch (IOException e) {
            System.out.println("保存账户信息时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}