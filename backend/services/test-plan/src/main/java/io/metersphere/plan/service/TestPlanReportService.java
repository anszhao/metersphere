package io.metersphere.plan.service;

import com.google.common.collect.Maps;
import io.metersphere.bug.dto.response.BugDTO;
import io.metersphere.bug.service.BugCommonService;
import io.metersphere.plan.constants.AssociateCaseType;
import io.metersphere.plan.constants.CollectionQueryType;
import io.metersphere.plan.domain.*;
import io.metersphere.plan.dto.*;
import io.metersphere.plan.dto.request.*;
import io.metersphere.plan.dto.response.TestPlanCaseExecHistoryResponse;
import io.metersphere.plan.dto.response.TestPlanReportDetailCollectionResponse;
import io.metersphere.plan.dto.response.TestPlanReportDetailResponse;
import io.metersphere.plan.dto.response.TestPlanReportPageResponse;
import io.metersphere.plan.enums.TestPlanReportAttachmentSourceType;
import io.metersphere.plan.mapper.*;
import io.metersphere.plan.utils.CountUtils;
import io.metersphere.plan.utils.RateCalculateUtils;
import io.metersphere.plugin.platform.dto.SelectOption;
import io.metersphere.sdk.constants.*;
import io.metersphere.sdk.exception.MSException;
import io.metersphere.sdk.file.FileCenter;
import io.metersphere.sdk.file.FileCopyRequest;
import io.metersphere.sdk.file.FileRepository;
import io.metersphere.sdk.file.FileRequest;
import io.metersphere.sdk.util.*;
import io.metersphere.system.domain.User;
import io.metersphere.system.dto.sdk.OptionDTO;
import io.metersphere.system.mapper.BaseUserMapper;
import io.metersphere.system.mapper.UserMapper;
import io.metersphere.system.notice.constants.NoticeConstants;
import io.metersphere.system.service.CommonFileService;
import io.metersphere.system.service.FileService;
import io.metersphere.system.service.SimpleUserService;
import io.metersphere.system.uid.IDGenerator;
import io.metersphere.system.utils.ServiceUtils;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class TestPlanReportService {

	@Resource
	private UserMapper userMapper;
	@Resource
	private SimpleUserService simpleUserService;
	@Resource
	private SqlSessionFactory sqlSessionFactory;
	@Resource
	private BugCommonService bugCommonService;
	@Resource
	private TestPlanMapper testPlanMapper;
	@Resource
	private TestPlanConfigMapper testPlanConfigMapper;
	@Resource
	private TestPlanReportMapper testPlanReportMapper;
	@Resource
	private ExtTestPlanReportMapper extTestPlanReportMapper;
	@Resource
	private FileService fileService;
	@Resource
	private TestPlanReportAttachmentMapper testPlanReportAttachmentMapper;
	@Resource
	private ExtTestPlanReportBugMapper extTestPlanReportBugMapper;
	@Resource
	private ExtTestPlanReportFunctionalCaseMapper extTestPlanReportFunctionalCaseMapper;
	@Resource
	private TestPlanCaseExecuteHistoryMapper testPlanCaseExecuteHistoryMapper;
	@Resource
	private ExtTestPlanReportApiCaseMapper extTestPlanReportApiCaseMapper;
	@Resource
	private ExtTestPlanReportApiScenarioMapper extTestPlanReportApiScenarioMapper;
	@Resource
	private TestPlanReportLogService testPlanReportLogService;
	@Resource
	private TestPlanReportNoticeService testPlanReportNoticeService;
	@Resource
	private TestPlanReportSummaryMapper testPlanReportSummaryMapper;
	@Resource
	private TestPlanReportFunctionCaseMapper testPlanReportFunctionCaseMapper;
	@Resource
	private TestPlanReportApiCaseMapper testPlanReportApiCaseMapper;
	@Resource
	private TestPlanReportApiScenarioMapper testPlanReportApiScenarioMapper;
	@Resource
	private TestPlanReportBugMapper testPlanReportBugMapper;
	@Resource
	private BaseUserMapper baseUserMapper;
	@Resource
	private TestPlanSendNoticeService testPlanSendNoticeService;
	@Resource
	private ExtTestPlanCaseExecuteHistoryMapper extTestPlanCaseExecuteHistoryMapper;
	@Resource
	private TestPlanReportComponentMapper componentMapper;
	@Resource
	private CommonFileService commonFileService;

	private static final int MAX_REPORT_NAME_LENGTH = 300;

	/**
	 * 分页查询报告列表
	 *
	 * @param request 分页请求参数
	 * @return 报告列表
	 */
	public List<TestPlanReportPageResponse> page(TestPlanReportPageRequest request) {
		List<TestPlanReportPageResponse> reportList = extTestPlanReportMapper.list(request);
		if (CollectionUtils.isEmpty(reportList)) {
			return new ArrayList<>();
		}
		List<String> distinctUserIds = reportList.stream().map(TestPlanReportPageResponse::getCreateUser).distinct().toList();
		Map<String, String> userMap = simpleUserService.getUserMapByIds(distinctUserIds);
		reportList.forEach(report -> report.setCreateUserName(userMap.get(report.getCreateUser())));
		return reportList;
	}

	/**
	 * 报告重命名
	 */
	public void rename(String id, String name) {
		if (name.length() > MAX_REPORT_NAME_LENGTH) {
			throw new MSException(Translator.get("test_plan_report_name_length_range"));
		}
		TestPlanReport report = checkReport(id);
		report.setName(name);
		testPlanReportMapper.updateByPrimaryKeySelective(report);
	}

	/**
	 * 业务删除报告
	 */
	public void setReportDelete(String id) {
		TestPlanReport report = checkReport(id);
		report.setDeleted(true);
		testPlanReportMapper.updateByPrimaryKeySelective(report);
	}

	/**
	 * 批量参数报告
	 *
	 * @param request 请求参数
	 */
	public void batchSetReportDelete(TestPlanReportBatchRequest request, String userId) {
		List<String> batchIds = getBatchIds(request);
		User user = userMapper.selectByPrimaryKey(userId);
		if (CollectionUtils.isNotEmpty(batchIds)) {
			SubListUtils.dealForSubList(batchIds, SubListUtils.DEFAULT_BATCH_SIZE, subList -> {
				TestPlanReportExample example = new TestPlanReportExample();
				example.createCriteria().andIdIn(subList);
				TestPlanReport testPlanReport = new TestPlanReport();
				testPlanReport.setDeleted(true);
				testPlanReportMapper.updateByExampleSelective(testPlanReport, example);
				testPlanReportLogService.batchDeleteLog(subList, userId, request.getProjectId());
				testPlanReportNoticeService.batchSendNotice(subList, user, request.getProjectId(), NoticeConstants.Event.DELETE);
			});
		}
	}

	/**
	 * 清空测试计划报告（包括summary
	 *
	 * @param reportIdList 报告ID集合
	 */
	public void cleanAndDeleteReport(List<String> reportIdList) {
		if (CollectionUtils.isNotEmpty(reportIdList)) {
			SubListUtils.dealForSubList(reportIdList, SubListUtils.DEFAULT_BATCH_SIZE, subList -> {
				TestPlanReportExample example = new TestPlanReportExample();
				example.createCriteria().andIdIn(subList);
				TestPlanReport testPlanReport = new TestPlanReport();
				testPlanReport.setDeleted(true);
				testPlanReportMapper.updateByExampleSelective(testPlanReport, example);

				this.deleteTestPlanReportBlobs(subList);
			});
		}
	}

	/**
	 * 删除测试计划报告（包括汇总, 明细)
	 */
	public void deleteByTestPlanIds(List<String> testPlanIds) {
		if (CollectionUtils.isNotEmpty(testPlanIds)) {
			TestPlanReportExample reportExample = new TestPlanReportExample();
			reportExample.createCriteria().andTestPlanIdIn(testPlanIds);
			List<TestPlanReport> testPlanReports = testPlanReportMapper.selectByExample(reportExample);
			if (CollectionUtils.isNotEmpty(testPlanReports)) {
				Map<String, TestPlanReport> reportMap = testPlanReports.stream().collect(Collectors.toMap(TestPlanReport::getId, r -> r));
				List<String> reportIdList = testPlanReports.stream().map(TestPlanReport::getId).toList();
				reportIdList.forEach(reportId -> {
					/*
					 * 独立计划直接删除; 子计划的报告删除时, 保留报告记录
					 */
					TestPlanReport report = reportMap.get(reportId);
					if (StringUtils.isNotBlank(report.getParentId()) && !report.getIntegrated() && !report.getDeleted()) {
						TestPlanReport record = new TestPlanReport();
						record.setId(reportId);
						record.setDeleted(true);
						testPlanReportMapper.updateByPrimaryKeySelective(record);
					} else {
						extTestPlanReportMapper.deleteGroupReport(reportId);
					}
				});
				// 清除汇总, 明细数据
				this.deleteTestPlanReportBlobs(reportIdList);
			}
		}
	}

	/**
	 * 删除报告内容
	 *
	 * @param reportIdList 报告ID集合
	 */
	private void deleteTestPlanReportBlobs(List<String> reportIdList) {
		TestPlanReportSummaryExample summaryExample = new TestPlanReportSummaryExample();
		summaryExample.createCriteria().andTestPlanReportIdIn(reportIdList);
		testPlanReportSummaryMapper.deleteByExample(summaryExample);

		TestPlanReportFunctionCaseExample testPlanReportFunctionCaseExample = new TestPlanReportFunctionCaseExample();
		testPlanReportFunctionCaseExample.createCriteria().andTestPlanReportIdIn(reportIdList);
		testPlanReportFunctionCaseMapper.deleteByExample(testPlanReportFunctionCaseExample);

		TestPlanReportApiCaseExample testPlanReportApiCaseExample = new TestPlanReportApiCaseExample();
		testPlanReportApiCaseExample.createCriteria().andTestPlanReportIdIn(reportIdList);
		testPlanReportApiCaseMapper.deleteByExample(testPlanReportApiCaseExample);

		TestPlanReportApiScenarioExample testPlanReportApiScenarioExample = new TestPlanReportApiScenarioExample();
		testPlanReportApiScenarioExample.createCriteria().andTestPlanReportIdIn(reportIdList);
		testPlanReportApiScenarioMapper.deleteByExample(testPlanReportApiScenarioExample);

		TestPlanReportBugExample testPlanReportBugExample = new TestPlanReportBugExample();
		testPlanReportBugExample.createCriteria().andTestPlanReportIdIn(reportIdList);
		testPlanReportBugMapper.deleteByExample(testPlanReportBugExample);
	}

	/**
	 * 手动生成报告 (计划 或者 组)
	 *
	 * @param request     请求参数
	 * @param currentUser 当前用户
	 */
	public String genReportByManual(TestPlanReportManualRequest request, String currentUser) {
		/*
		 * 1. 生成报告 (全量生成; 暂不根据布局来选择生成报告预览数据, 因为影响分析汇总)
		 * 2. 保存报告布局组件 (只对当前生成的计划/组有效, 不会对下面的子计划报告生效)
		 * 3. 处理富文本图片
		 */
		Map<String, String> reportMap = genReport(IDGenerator.nextStr(), request, true, currentUser, request.getReportName());
		String genReportId = reportMap.get(request.getTestPlanId());
		List<TestPlanReportComponentSaveRequest> components = request.getComponents();
		if (CollectionUtils.isNotEmpty(components)) {
			SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
			TestPlanReportComponentMapper batchMapper = sqlSession.getMapper(TestPlanReportComponentMapper.class);
			List<TestPlanReportComponent> reportComponents = new ArrayList<>();
			components.forEach(component -> {
				TestPlanReportComponent reportComponent = new TestPlanReportComponent();
				BeanUtils.copyBean(reportComponent, component);
				reportComponent.setId(IDGenerator.nextStr());
				reportComponent.setTestPlanReportId(genReportId);
				reportComponents.add(reportComponent);
			});
			batchMapper.batchInsert(reportComponents);
			sqlSession.flushStatements();
			SqlSessionUtils.closeSqlSession(sqlSession, sqlSessionFactory);
		}
		// 更新报告默认布局字段
		TestPlanReport record = new TestPlanReport();
		record.setId(genReportId);
		record.setDefaultLayout(false);
		testPlanReportMapper.updateByPrimaryKeySelective(record);
		// 处理富文本文件
		transferRichTextTmpFile(genReportId, request.getProjectId(), request.getRichTextTmpFileIds(), currentUser, TestPlanReportAttachmentSourceType.RICH_TEXT.name());
		return reportMap.get(request.getTestPlanId());
	}

	/**
	 * 自动生成报告 (计划 或者 组)
	 *
	 * @param request     请求参数
	 * @param currentUser 当前用户
	 */
	public String genReportByAuto(TestPlanReportGenRequest request, String currentUser) {
		Map<String, String> reportMap = genReport(IDGenerator.nextStr(), request, true, currentUser, null);
		return reportMap.get(request.getTestPlanId());
	}

	/**
	 * 执行生成报告
	 * 新开事务，避免异步执行查不到数据
	 *
	 * @param request     请求参数
	 * @param currentUser 当前用户
	 */
	@Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
	public Map<String, String> genReportByExecution(String prepareReportId, TestPlanReportGenRequest request, String currentUser) {
		return genReport(prepareReportId, request, false, currentUser, null);
	}

	/**
	 * 生成报告
	 *
	 * @param prepareReportId  预生成报告ID
	 * @param request          请求参数
	 * @param manual           是否手动生成
	 * @param currentUser      当前用户
	 * @param manualReportName 手动生成报告名称
	 */
	public Map<String, String> genReport(String prepareReportId, TestPlanReportGenRequest request, boolean manual, String currentUser, String manualReportName) {
		Map<String, String> preReportMap = Maps.newHashMapWithExpectedSize(8);
		TestPlanReportManualParam reportManualParam = TestPlanReportManualParam.builder().manualName(manualReportName).targetId(request.getTestPlanId()).build();
		try {
			// 所有计划
			List<TestPlan> plans = getPlans(request.getTestPlanId());

			/*
			 * 1. 准备报告生成参数
			 * 2. 预生成报告
			 * 3. 汇总报告数据 {执行时跳过}
			 * 3. 报告后置处理 (计算通过率, 执行率, 执行状态...) {执行时跳过}
			 */
			List<String> childPlanIds = plans.stream().filter(plan -> StringUtils.equals(plan.getType(), TestPlanConstants.TEST_PLAN_TYPE_PLAN)).map(TestPlan::getId).toList();

			boolean isGroupReports = plans.size() > 1;
			plans.forEach(plan -> {
				request.setTestPlanId(plan.getId());
				TestPlanReportGenPreParam genPreParam = buildReportGenParam(request, plan, prepareReportId);
				if (!manual) {
					// 不是手动保存的测试计划报告，不存储startTime
					genPreParam.setStartTime(null);
				}
				genPreParam.setUseManual(manual);
				// 如果是测试计划的独立报告，使用参数中的预生成的报告id。否则只有测试计划组报告使用该id
				String prepareItemReportId = isGroupReports ? IDGenerator.nextStr() : prepareReportId;
				TestPlanReport preReport = preGenReport(prepareItemReportId, genPreParam, currentUser, childPlanIds, reportManualParam);
				if (manual) {
					// 汇总
					if (genPreParam.getIntegrated()) {
						summaryGroupReport(preReport.getId());
					} else {
						summaryPlanReport(preReport.getId());
					}
					// 手动生成的报告, 汇总结束后直接进行后置处理
					TestPlanReportPostParam postParam = new TestPlanReportPostParam();
					postParam.setReportId(preReport.getId());
					// 手动生成报告, 执行状态为已完成, 执行及结束时间为当前时间
					postParam.setExecuteTime(System.currentTimeMillis());
					postParam.setEndTime(System.currentTimeMillis());
					postParam.setExecStatus(ExecStatus.COMPLETED.name());
					postHandleReport(postParam, true);
				}
				preReportMap.put(plan.getId(), preReport.getId());
			});
		} catch (Exception e) {
			LogUtils.error("Generate report exception: " + e.getMessage());
		}

		return preReportMap;
	}

	/**
	 * 预生成报告内容(汇总前调用)
	 *
	 * @return 报告
	 */
	public TestPlanReport preGenReport(String prepareId, TestPlanReportGenPreParam genParam, String currentUser, List<String> childPlanIds,
									   TestPlanReportManualParam reportManualParam) {
		// 计划配置
		TestPlanConfig config = testPlanConfigMapper.selectByPrimaryKey(genParam.getTestPlanId());

		/*
		 * 预生成报告
		 * 1. 生成报告用例数据, 缺陷数据
		 * 2. 生成或计算报告统计数据
		 */
		TestPlanReport report = new TestPlanReport();
		BeanUtils.copyBean(report, genParam);
		report.setId(genParam.getIntegrated() ? genParam.getGroupReportId() : prepareId);
		report.setName((StringUtils.equals(genParam.getTestPlanId(), reportManualParam.getTargetId()) && StringUtils.isNotBlank(reportManualParam.getManualName())) ?
				reportManualParam.getManualName() : genParam.getTestPlanName() + "-" + DateUtils.getTimeStr(System.currentTimeMillis()));
		report.setCreateUser(currentUser);
		report.setCreateTime(System.currentTimeMillis());
		report.setDeleted(false);
		report.setPassThreshold(config == null ? null : config.getPassThreshold());
		report.setParentId(genParam.getGroupReportId());
		report.setTestPlanName(genParam.getTestPlanName());
		testPlanReportMapper.insertSelective(report);

		TestPlanReportDetailCaseDTO reportCaseDetail;
		if (!genParam.getIntegrated()) {
			// 生成独立报告的关联数据
			reportCaseDetail = genReportDetail(genParam, report);
		} else {
			// 计划组报告暂不统计各用例类型, 汇总时再入库
			reportCaseDetail = TestPlanReportDetailCaseDTO.builder().build();
		}
		// 报告统计内容
		TestPlanReportSummary reportSummary = new TestPlanReportSummary();
		reportSummary.setId(IDGenerator.nextStr());
		reportSummary.setTestPlanReportId(report.getId());
		reportSummary.setFunctionalCaseCount(reportCaseDetail.getFunctionCaseCount());
		reportSummary.setApiCaseCount(reportCaseDetail.getApiCaseCount());
		reportSummary.setApiScenarioCount(reportCaseDetail.getApiScenarioCount());
		reportSummary.setBugCount(reportCaseDetail.getBugCount());
		reportSummary.setPlanCount(genParam.getIntegrated() ? (long) childPlanIds.size() : 0);
		testPlanReportSummaryMapper.insertSelective(reportSummary);

		// 报告日志
		testPlanReportLogService.addLog(report, currentUser, genParam.getProjectId());
		return report;
	}

	/**
	 * 生成独立报告的关联数据
	 *
	 * @param genParam 报告生成的参数
	 * @param report   报告
	 */
	private TestPlanReportDetailCaseDTO genReportDetail(TestPlanReportGenPreParam genParam, TestPlanReport report) {
		SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
		// 缺陷数
		List<ReportBugCountDTO> bugCountList = extTestPlanReportBugMapper.countPlanBug(genParam.getTestPlanId());
		Map<String, Long> bugCountMap = bugCountList.stream().collect(Collectors.toMap(ReportBugCountDTO::getRefCaseId, ReportBugCountDTO::getBugCount));

		AtomicLong funcCaseCount = new AtomicLong();
		AtomicLong apiCaseCount = new AtomicLong();
		AtomicLong apiScenarioCount = new AtomicLong();
		AtomicLong bugCount = new AtomicLong();
		// 功能用例
		{
			List<String> funcCaseIdList = extTestPlanReportFunctionalCaseMapper.getPlanExecuteCasesId(genParam.getTestPlanId());
			if (CollectionUtils.isNotEmpty(funcCaseIdList)) {
				SubListUtils.dealForSubList(funcCaseIdList, 200, (subList) -> {
					if (CollectionUtils.isEmpty(subList)) {
						return;
					}
					List<TestPlanReportFunctionCase> reportFunctionCases = extTestPlanReportFunctionalCaseMapper.getPlanExecuteCases(genParam.getTestPlanId(), subList);
					funcCaseCount.addAndGet(reportFunctionCases.size());
					// 用例等级
					List<String> ids = reportFunctionCases.stream().map(TestPlanReportFunctionCase::getFunctionCaseId).distinct().toList();
					List<SelectOption> options = extTestPlanReportFunctionalCaseMapper.getCasePriorityByIds(ids);
					Map<String, String> casePriorityMap = options.stream().collect(Collectors.toMap(SelectOption::getValue, SelectOption::getText));
					// 用例模块
					List<String> moduleIds = reportFunctionCases.stream().map(TestPlanReportFunctionCase::getFunctionCaseModule).filter(Objects::nonNull).toList();
					Map<String, String> moduleMap = getModuleMapByIds(moduleIds, CaseType.FUNCTIONAL_CASE.getKey());
					// 关联的功能用例最新一次执行历史
					List<String> relateIds = reportFunctionCases.stream().map(TestPlanReportFunctionCase::getTestPlanFunctionCaseId).toList();
					TestPlanCaseExecuteHistoryExample example = new TestPlanCaseExecuteHistoryExample();
					example.createCriteria().andTestPlanCaseIdIn(relateIds);
					List<TestPlanCaseExecuteHistory> functionalExecHisList = testPlanCaseExecuteHistoryMapper.selectByExample(example);
					Map<String, List<TestPlanCaseExecuteHistory>> functionalExecMap = functionalExecHisList.stream().collect(Collectors.groupingBy(TestPlanCaseExecuteHistory::getTestPlanCaseId));

					for (TestPlanReportFunctionCase reportFunctionalCase : reportFunctionCases) {
						reportFunctionalCase.setId(IDGenerator.nextStr());
						reportFunctionalCase.setTestPlanReportId(report.getId());
						reportFunctionalCase.setTestPlanName(genParam.getTestPlanName());
						if (reportFunctionalCase.getFunctionCaseModule() != null) {
							reportFunctionalCase.setFunctionCaseModule(moduleMap.getOrDefault(reportFunctionalCase.getFunctionCaseModule(), reportFunctionalCase.getFunctionCaseModule()));
						}
						reportFunctionalCase.setFunctionCasePriority(casePriorityMap.get(reportFunctionalCase.getFunctionCaseId()));
						List<TestPlanCaseExecuteHistory> hisList = functionalExecMap.get(reportFunctionalCase.getTestPlanFunctionCaseId());
						if (CollectionUtils.isNotEmpty(hisList)) {
							Optional<String> lastExecuteHisOpt = hisList.stream().sorted(Comparator.comparing(TestPlanCaseExecuteHistory::getCreateTime).reversed()).map(TestPlanCaseExecuteHistory::getId).findFirst();
							reportFunctionalCase.setFunctionCaseExecuteReportId(lastExecuteHisOpt.orElse(null));
						} else {
							reportFunctionalCase.setFunctionCaseExecuteReportId(null);
						}
						reportFunctionalCase.setFunctionCaseBugCount(bugCountMap.containsKey(reportFunctionalCase.getTestPlanFunctionCaseId()) ? bugCountMap.get(reportFunctionalCase.getTestPlanFunctionCaseId()) : 0);
					}

					// 插入计划功能用例关联数据 -> 报告内容
					TestPlanReportFunctionCaseMapper batchMapper = sqlSession.getMapper(TestPlanReportFunctionCaseMapper.class);
					batchMapper.batchInsert(reportFunctionCases);
				});
			}
			funcCaseIdList = null;
		}

		// 接口用例
		{
			List<String> testPlanReportApiCaseIdList = extTestPlanReportApiCaseMapper.getPlanExecuteCasesId(genParam.getTestPlanId());
			if (CollectionUtils.isNotEmpty(testPlanReportApiCaseIdList)) {
				SubListUtils.dealForSubList(testPlanReportApiCaseIdList, 200, (subList) -> {
					if (CollectionUtils.isEmpty(subList)) {
						return;
					}
					List<TestPlanReportApiCase> reportApiCases = extTestPlanReportApiCaseMapper.getPlanExecuteCases(genParam.getTestPlanId(), subList);
					apiCaseCount.addAndGet(reportApiCases.size());
					List<String> moduleIds = reportApiCases.stream().map(TestPlanReportApiCase::getApiCaseModule).filter(Objects::nonNull).distinct().toList();
					Map<String, String> moduleMap = getModuleMapByIds(moduleIds, CaseType.API_CASE.getKey());

					for (TestPlanReportApiCase reportApiCase : reportApiCases) {
						reportApiCase.setId(IDGenerator.nextStr());
						reportApiCase.setTestPlanReportId(report.getId());
						reportApiCase.setTestPlanName(genParam.getTestPlanName());
						if (reportApiCase.getApiCaseModule() != null) {
							reportApiCase.setApiCaseModule(moduleMap.getOrDefault(reportApiCase.getApiCaseModule(), reportApiCase.getApiCaseModule()));
						}
						// 根据不超过数据库字段最大长度压缩模块名
						reportApiCase.setApiCaseModule(ServiceUtils.compressName(reportApiCase.getApiCaseModule(), 450));
						if (!genParam.getUseManual()) {
							// 接口执行时才更新结果
							reportApiCase.setApiCaseExecuteResult(null);
							reportApiCase.setApiCaseExecuteUser(null);
							reportApiCase.setApiCaseExecuteReportId(IDGenerator.nextStr());
						}
						reportApiCase.setApiCaseBugCount(bugCountMap.containsKey(reportApiCase.getTestPlanApiCaseId()) ? bugCountMap.get(reportApiCase.getTestPlanApiCaseId()) : 0);
					}
					// 插入计划接口用例关联数据 -> 报告内容
					TestPlanReportApiCaseMapper batchMapper = sqlSession.getMapper(TestPlanReportApiCaseMapper.class);
					batchMapper.batchInsert(reportApiCases);
					sqlSession.flushStatements();
				});
			}
			testPlanReportApiCaseIdList = null;
		}

		// 场景用例
		{
			List<String> reportApiScenarioIdList = extTestPlanReportApiScenarioMapper.getPlanExecuteCasesId(genParam.getTestPlanId());
			if (CollectionUtils.isNotEmpty(reportApiScenarioIdList)) {
				SubListUtils.dealForSubList(reportApiScenarioIdList, 200, (subList) -> {
					if (CollectionUtils.isEmpty(subList)) {
						return;
					}
					List<TestPlanReportApiScenario> reportApiScenarios = extTestPlanReportApiScenarioMapper.getPlanExecuteCases(genParam.getTestPlanId(), subList);
					apiScenarioCount.addAndGet(reportApiScenarios.size());
					List<String> moduleIds = reportApiScenarios.stream().map(TestPlanReportApiScenario::getApiScenarioModule).filter(Objects::nonNull).distinct().toList();
					Map<String, String> moduleMap = getModuleMapByIds(moduleIds, CaseType.SCENARIO_CASE.getKey());
					for (TestPlanReportApiScenario reportApiScenario : reportApiScenarios) {
						reportApiScenario.setId(IDGenerator.nextStr());
						reportApiScenario.setTestPlanReportId(report.getId());
						reportApiScenario.setTestPlanName(genParam.getTestPlanName());
						if (reportApiScenario.getApiScenarioModule() != null) {
							reportApiScenario.setApiScenarioModule(moduleMap.getOrDefault(reportApiScenario.getApiScenarioModule(), reportApiScenario.getApiScenarioModule()));
						}
						// 根据不超过数据库字段最大长度压缩模块名
						reportApiScenario.setApiScenarioModule(ServiceUtils.compressName(reportApiScenario.getApiScenarioModule(), 450));
						if (!genParam.getUseManual()) {
							// 接口执行时才更新结果
							reportApiScenario.setApiScenarioExecuteResult(null);
							reportApiScenario.setApiScenarioExecuteUser(null);
							reportApiScenario.setApiScenarioExecuteReportId(IDGenerator.nextStr());
						}
						reportApiScenario.setApiScenarioBugCount(bugCountMap.containsKey(reportApiScenario.getTestPlanApiScenarioId()) ? bugCountMap.get(reportApiScenario.getTestPlanApiScenarioId()) : 0);
					}
					// 插入计划场景用例关联数据 -> 报告内容
					TestPlanReportApiScenarioMapper batchMapper = sqlSession.getMapper(TestPlanReportApiScenarioMapper.class);
					batchMapper.batchInsert(reportApiScenarios);
					sqlSession.flushStatements();
				});
			}
			reportApiScenarioIdList = null;
		}

		// 计划报告缺陷内容
		{
			List<String> reportBugIdList = extTestPlanReportBugMapper.getPlanBugsId(genParam.getTestPlanId());
			if (CollectionUtils.isNotEmpty(reportBugIdList)) {
				SubListUtils.dealForSubList(reportBugIdList, 200, (subList) -> {
					if (CollectionUtils.isEmpty(subList)) {
						return;
					}
					List<TestPlanReportBug> reportBugs = extTestPlanReportBugMapper.getPlanBugs(genParam.getTestPlanId(), subList);
					bugCount.addAndGet(reportBugs.size());
					// MS处理人会与第三方的值冲突, 分开查询
					List<SelectOption> headerOptions = bugCommonService.getHeaderHandlerOption(genParam.getProjectId());
					Map<String, String> headerHandleUserMap = headerOptions.stream().collect(Collectors.toMap(SelectOption::getValue, SelectOption::getText));
					List<SelectOption> localOptions = bugCommonService.getLocalHandlerOption(genParam.getProjectId());
					Map<String, String> localHandleUserMap = localOptions.stream().collect(Collectors.toMap(SelectOption::getValue, SelectOption::getText));
					Map<String, String> allStatusMap = bugCommonService.getAllStatusMap(genParam.getProjectId());
					reportBugs.forEach(reportBug -> {
						reportBug.setId(IDGenerator.nextStr());
						reportBug.setTestPlanReportId(report.getId());
						reportBug.setBugHandleUser(headerHandleUserMap.containsKey(reportBug.getBugHandleUser()) ?
								headerHandleUserMap.get(reportBug.getBugHandleUser()) : localHandleUserMap.get(reportBug.getBugHandleUser()));
						reportBug.setBugStatus(allStatusMap.get(reportBug.getBugStatus()));
					});
					// 插入计划关联用例缺陷数据(去重) -> 报告内容
					TestPlanReportBugMapper batchMapper = sqlSession.getMapper(TestPlanReportBugMapper.class);
					batchMapper.batchInsert(reportBugs);
					sqlSession.flushStatements();
				});
			}
		}

		long beforeFlush = System.currentTimeMillis();
		sqlSession.flushStatements();
		long beforeClose = System.currentTimeMillis();
		System.out.println("flush time: " + (beforeClose - beforeFlush));
		SqlSessionUtils.closeSqlSession(sqlSession, sqlSessionFactory);
		long afterFlush = System.currentTimeMillis();
		System.out.println("close time: " + (afterFlush - beforeClose));

		return TestPlanReportDetailCaseDTO.builder()
				.functionCaseCount(funcCaseCount.get()).apiCaseCount(apiCaseCount.get()).apiScenarioCount(apiScenarioCount.get()).bugCount(bugCount.get()).build();
	}


	/**
	 * 报告结果后置处理 (汇总操作结束后调用)
	 *
	 * @param postParam 后置处理参数
	 */
	public void postHandleReport(TestPlanReportPostParam postParam, boolean useManual) {
		/*
		 * 处理报告(执行状态, 结束时间)
		 */
		TestPlanReport planReport = checkReport(postParam.getReportId());
		BeanUtils.copyBean(planReport, postParam);
		/*
		 * 计算报告通过率, 并对比阈值生成报告结果状态
		 */
		TestPlanReportSummaryExample example = new TestPlanReportSummaryExample();
		example.createCriteria().andTestPlanReportIdEqualTo(postParam.getReportId());
		TestPlanReportSummary reportSummary = testPlanReportSummaryMapper.selectByExampleWithBLOBs(example).getFirst();
		// 用例总数
		long caseTotal = reportSummary.getFunctionalCaseCount() + reportSummary.getApiCaseCount() + reportSummary.getApiScenarioCount();
		CaseCount summaryCount = JSON.parseObject(new String(reportSummary.getExecuteResult()), CaseCount.class);
		planReport.setExecuteRate(RateCalculateUtils.divWithPrecision(((int) caseTotal - summaryCount.getPending()), (int) caseTotal, 2));
		planReport.setPassRate(RateCalculateUtils.divWithPrecision(summaryCount.getSuccess(), (int) caseTotal, 2));
		if (planReport.getIntegrated()) {
			// 计划组的(执行)结果状态: 子计划全部成功 ? 成功 : 失败
			TestPlanReportExample reportExample = new TestPlanReportExample();
			reportExample.createCriteria().andParentIdEqualTo(postParam.getReportId()).andIntegratedEqualTo(false).andResultStatusNotEqualTo(ResultStatus.SUCCESS.name());
			planReport.setResultStatus(testPlanReportMapper.countByExample(reportExample) == 0 ? ResultStatus.SUCCESS.name() : ResultStatus.ERROR.name());
		} else {
			// 计划的(执行)结果状态: 通过率 >= 阈值 ? 成功 : 失败
			planReport.setResultStatus(planReport.getPassRate() >= planReport.getPassThreshold() ? ResultStatus.SUCCESS.name() : ResultStatus.ERROR.name());
		}

		testPlanReportMapper.updateByPrimaryKeySelective(planReport);

		// 发送计划执行通知
		if (!useManual) {
			testPlanSendNoticeService.sendExecuteNotice(planReport.getCreateUser(), planReport.getTestPlanId(), planReport);
		}
	}

	/**
	 * 获取报告
	 *
	 * @param reportId 报告ID
	 * @return 报告详情
	 */
	public TestPlanReport selectById(String reportId) {
		return testPlanReportMapper.selectByPrimaryKey(reportId);
	}

	/**
	 * 获取报告分析详情
	 *
	 * @param reportId 报告ID
	 * @return 报告分析详情
	 */
	public TestPlanReportDetailResponse getReport(String reportId) {
		TestPlanReport planReport = checkReport(reportId);
		TestPlanReportSummaryExample example = new TestPlanReportSummaryExample();
		example.createCriteria().andTestPlanReportIdEqualTo(reportId);
		List<TestPlanReportSummary> testPlanReportSummaries = testPlanReportSummaryMapper.selectByExampleWithBLOBs(example);
		if (CollectionUtils.isEmpty(testPlanReportSummaries)) {
			throw new MSException(Translator.get("test_plan_report_detail_not_exist"));
		}
		TestPlanReportSummary reportSummary = testPlanReportSummaries.getFirst();
		TestPlanReportDetailResponse planReportDetail = new TestPlanReportDetailResponse();
		BeanUtils.copyBean(planReportDetail, planReport);
		// 用例总数需单独返回, 不然前端表格不展示, 影响执行中的数据
		int caseTotal = (int) (reportSummary.getFunctionalCaseCount() + reportSummary.getApiCaseCount() + reportSummary.getApiScenarioCount());
		planReportDetail.setCaseTotal(caseTotal);
		planReportDetail.setFunctionalTotal(reportSummary.getFunctionalCaseCount().intValue());
		planReportDetail.setApiCaseTotal(reportSummary.getApiCaseCount().intValue());
		planReportDetail.setApiScenarioTotal(reportSummary.getApiScenarioCount().intValue());
		planReportDetail.setBugCount(reportSummary.getBugCount().intValue());
		//用例关联缺陷数
		List<ReportBugSumDTO> bugSumList = extTestPlanReportBugMapper.countBug(reportId);
		Map<String, Long> bugSumMap = bugSumList.stream().collect(Collectors.toMap(ReportBugSumDTO::getCaseType, ReportBugSumDTO::getBugCount));
		planReportDetail.setFunctionalBugCount(bugSumMap.get(CaseType.FUNCTIONAL_CASE.getKey()).intValue());
		planReportDetail.setApiBugCount(bugSumMap.get(CaseType.API_CASE.getKey()).intValue());
		planReportDetail.setScenarioBugCount(bugSumMap.get(CaseType.SCENARIO_CASE.getKey()).intValue());

		if (planReport.getIntegrated()) {
			// 计划组报告, 需要统计计划的执行数据
			planReportDetail.setPlanCount(reportSummary.getPlanCount().intValue());
			TestPlanReportExample reportExample = new TestPlanReportExample();
			reportExample.createCriteria().andParentIdEqualTo(reportId).andIntegratedEqualTo(false);
			List<TestPlanReport> testPlanReports = testPlanReportMapper.selectByExample(reportExample);
			long planPassCount = testPlanReports.stream().filter(report -> StringUtils.equals(ResultStatus.SUCCESS.name(), report.getResultStatus())).count();
			planReportDetail.setPassCountOfPlan((int) planPassCount);
			planReportDetail.setFailCountOfPlan(planReportDetail.getPlanCount() - planReportDetail.getPassCountOfPlan());
		}
		planReportDetail.setSummary(reportSummary.getSummary());
		/*
		 * 统计用例执行数据
		 */
		planReportDetail.setFunctionalCount(reportSummary.getFunctionalExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(reportSummary.getFunctionalExecuteResult()), CaseCount.class));
		planReportDetail.setApiCaseCount(reportSummary.getApiExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(reportSummary.getApiExecuteResult()), CaseCount.class));
		planReportDetail.setApiScenarioCount(reportSummary.getScenarioExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(reportSummary.getScenarioExecuteResult()), CaseCount.class));
		planReportDetail.setExecuteCount(reportSummary.getExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(reportSummary.getExecuteResult()), CaseCount.class));
		return planReportDetail;
	}

	public List<TestPlanReportComponent> getLayout(String reportId) {
		TestPlanReportComponentExample example = new TestPlanReportComponentExample();
		example.createCriteria().andTestPlanReportIdEqualTo(reportId);
		return componentMapper.selectByExampleWithBLOBs(example);
	}

	/**
	 * 更新报告详情
	 *
	 * @param request 更新请求参数
	 * @return 报告详情
	 */
	public TestPlanReportDetailResponse edit(TestPlanReportDetailEditRequest request, String currentUser) {
		TestPlanReport planReport = checkReport(request.getId());
		if (planReport.getDefaultLayout()) {
			// 默认布局只存在报告总结
			TestPlanReportSummary reportSummary = new TestPlanReportSummary();
			reportSummary.setSummary(StringUtils.isBlank(request.getComponentValue()) ? StringUtils.EMPTY : request.getComponentValue());
			TestPlanReportSummaryExample example = new TestPlanReportSummaryExample();
			example.createCriteria().andTestPlanReportIdEqualTo(planReport.getId());
			testPlanReportSummaryMapper.updateByExampleSelective(reportSummary, example);
		} else {
			// 手动生成的布局, 只更新富文本组件的内容
			TestPlanReportComponent record = new TestPlanReportComponent();
			record.setId(request.getComponentId());
			record.setValue(request.getComponentValue());
			componentMapper.updateByPrimaryKeySelective(record);
		}
		// 处理富文本文件
		transferRichTextTmpFile(request.getId(), planReport.getProjectId(), request.getRichTextTmpFileIds(), currentUser, TestPlanReportAttachmentSourceType.RICH_TEXT.name());
		return getReport(planReport.getId());
	}

	/**
	 * 分页查询报告详情-缺陷分页数据
	 *
	 * @param request 请求参数
	 * @return 缺陷分页数据
	 */
	public List<BugDTO> listReportDetailBugs(TestPlanReportDetailPageRequest request) {
		return extTestPlanReportBugMapper.list(request);
	}

	/**
	 * 分页查询报告详情-用例分页数据
	 *
	 * @param request 请求参数
	 * @return 用例分页数据
	 */
	public List<ReportDetailCasePageDTO> listReportDetailCases(TestPlanReportDetailPageRequest request, String caseType) {
		List<ReportDetailCasePageDTO> detailCases;
		switch (caseType) {
			case AssociateCaseType.FUNCTIONAL -> detailCases = extTestPlanReportFunctionalCaseMapper.list(request);
			case AssociateCaseType.API_CASE -> detailCases = extTestPlanReportApiCaseMapper.list(request);
			case AssociateCaseType.API_SCENARIO -> detailCases = extTestPlanReportApiScenarioMapper.list(request);
			default -> detailCases = new ArrayList<>();
		}
		List<String> distinctUserIds = detailCases.stream().map(ReportDetailCasePageDTO::getExecuteUser).distinct().collect(Collectors.toList());
		distinctUserIds.removeIf(StringUtils::isEmpty);
		Map<String, String> userMap = getUserMap(distinctUserIds);
		detailCases.forEach(detailCase -> detailCase.setExecuteUser(userMap.getOrDefault(detailCase.getExecuteUser(), detailCase.getExecuteUser())));
		return detailCases;
	}

	/**
	 * 返回功能用例执行结果
	 *
	 * @param executeHisId 执行历史ID
	 * @return 执行结果
	 */
	public TestPlanCaseExecHistoryResponse getFunctionalExecuteResult(String executeHisId) {
		TestPlanCaseExecHistoryResponse singleExecHistory = extTestPlanCaseExecuteHistoryMapper.getSingleExecHistory(executeHisId);
		if (singleExecHistory.getContent() != null) {
			singleExecHistory.setContentText(new String(singleExecHistory.getContent(), StandardCharsets.UTF_8));
		}
		if (singleExecHistory.getSteps() != null) {
			singleExecHistory.setStepsExecResult(new String(singleExecHistory.getSteps(), StandardCharsets.UTF_8));
		}
		return singleExecHistory;
	}

	/**
	 * 汇总生成的计划报告
	 *
	 * @param reportId 报告ID
	 */
	public void summaryPlanReport(String reportId) {
		TestPlanReportSummaryExample example = new TestPlanReportSummaryExample();
		example.createCriteria().andTestPlanReportIdEqualTo(reportId);
		List<TestPlanReportSummary> testPlanReportSummaries = testPlanReportSummaryMapper.selectByExample(example);
		if (CollectionUtils.isEmpty(testPlanReportSummaries)) {
			// 报告详情不存在
			return;
		}
		// 功能用例
		List<CaseStatusCountMap> functionalCountMapList = extTestPlanReportFunctionalCaseMapper.countExecuteResult(reportId);
		CaseCount functionalCaseCount = countMap(functionalCountMapList);
		// 接口用例
		List<CaseStatusCountMap> apiCountMapList = extTestPlanReportApiCaseMapper.countExecuteResult(reportId);
		CaseCount apiCaseCount = countMap(apiCountMapList);
		// 场景用例
		List<CaseStatusCountMap> scenarioCountMapList = extTestPlanReportApiScenarioMapper.countExecuteResult(reportId);
		CaseCount scenarioCaseCount = countMap(scenarioCountMapList);

		// 规划整体的汇总数据
		CaseCount summaryCount = CaseCount.builder().success(functionalCaseCount.getSuccess() + apiCaseCount.getSuccess() + scenarioCaseCount.getSuccess())
				.error(functionalCaseCount.getError() + apiCaseCount.getError() + scenarioCaseCount.getError())
				.block(functionalCaseCount.getBlock() + apiCaseCount.getBlock() + scenarioCaseCount.getBlock())
				.pending(functionalCaseCount.getPending() + apiCaseCount.getPending() + scenarioCaseCount.getPending())
				.fakeError(functionalCaseCount.getFakeError() + apiCaseCount.getFakeError() + scenarioCaseCount.getFakeError()).build();

		// 入库汇总数据 => 报告详情表
		TestPlanReportSummary reportSummary = testPlanReportSummaries.getFirst();
		reportSummary.setFunctionalExecuteResult(JSON.toJSONBytes(functionalCaseCount));
		reportSummary.setApiExecuteResult(JSON.toJSONBytes(apiCaseCount));
		reportSummary.setScenarioExecuteResult(JSON.toJSONBytes(scenarioCaseCount));
		reportSummary.setExecuteResult(JSON.toJSONBytes(summaryCount));
		testPlanReportSummaryMapper.updateByPrimaryKeySelective(reportSummary);
	}

	/**
	 * 汇总生成的计划组报告
	 *
	 * @param reportId 报告ID
	 */
	public void summaryGroupReport(String reportId) {
		TestPlanReportSummaryExample summaryExample = new TestPlanReportSummaryExample();
		summaryExample.createCriteria().andTestPlanReportIdEqualTo(reportId);
		List<TestPlanReportSummary> testPlanReportSummaries = testPlanReportSummaryMapper.selectByExample(summaryExample);
		if (CollectionUtils.isEmpty(testPlanReportSummaries)) {
			// 报告详情不存在
			return;
		}
		TestPlanReportSummary groupSummary = testPlanReportSummaries.getFirst();

		TestPlanReportExample example = new TestPlanReportExample();
		example.createCriteria().andParentIdEqualTo(reportId).andIntegratedEqualTo(false);
		List<TestPlanReport> testPlanReports = testPlanReportMapper.selectByExample(example);
		if (CollectionUtils.isEmpty(testPlanReports)) {
			// 不存在子报告, 不需要汇总数据
			return;
		}

		// 汇总子报告关联的数据并入库
		SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
		List<String> ids = testPlanReports.stream().map(TestPlanReport::getId).toList();
		// 功能用例
		TestPlanReportFunctionCaseExample functionCaseExample = new TestPlanReportFunctionCaseExample();
		functionCaseExample.createCriteria().andTestPlanReportIdIn(ids);
		List<TestPlanReportFunctionCase> reportFunctionCases = testPlanReportFunctionCaseMapper.selectByExample(functionCaseExample);
		if (CollectionUtils.isNotEmpty(reportFunctionCases)) {
			groupSummary.setFunctionalCaseCount((long) reportFunctionCases.size());
			reportFunctionCases.forEach(reportFunctionCase -> {
				reportFunctionCase.setId(IDGenerator.nextStr());
				reportFunctionCase.setTestPlanReportId(reportId);
			});
			// 插入计划组报告, 功能用例关联数据
			TestPlanReportFunctionCaseMapper batchMapper = sqlSession.getMapper(TestPlanReportFunctionCaseMapper.class);
			batchMapper.batchInsert(reportFunctionCases);
		}
		// 接口用例
		TestPlanReportApiCaseExample apiCaseExample = new TestPlanReportApiCaseExample();
		apiCaseExample.createCriteria().andTestPlanReportIdIn(ids);
		List<TestPlanReportApiCase> reportApiCases = testPlanReportApiCaseMapper.selectByExample(apiCaseExample);
		if (CollectionUtils.isNotEmpty(reportApiCases)) {
			groupSummary.setApiCaseCount((long) reportApiCases.size());
			reportApiCases.forEach(reportApiCase -> {
				reportApiCase.setId(IDGenerator.nextStr());
				reportApiCase.setTestPlanReportId(reportId);
			});
			// 插入计划组报告, 接口用例关联数据
			TestPlanReportApiCaseMapper batchMapper = sqlSession.getMapper(TestPlanReportApiCaseMapper.class);
			batchMapper.batchInsert(reportApiCases);
		}
		// 场景用例
		TestPlanReportApiScenarioExample scenarioExample = new TestPlanReportApiScenarioExample();
		scenarioExample.createCriteria().andTestPlanReportIdIn(ids);
		List<TestPlanReportApiScenario> reportApiScenarios = testPlanReportApiScenarioMapper.selectByExample(scenarioExample);
		if (CollectionUtils.isNotEmpty(reportApiScenarios)) {
			groupSummary.setApiScenarioCount((long) reportApiScenarios.size());
			reportApiScenarios.forEach(reportApiScenario -> {
				reportApiScenario.setId(IDGenerator.nextStr());
				reportApiScenario.setTestPlanReportId(reportId);
			});
			// 插入计划组报告, 场景用例关联数据
			TestPlanReportApiScenarioMapper batchMapper = sqlSession.getMapper(TestPlanReportApiScenarioMapper.class);
			batchMapper.batchInsert(reportApiScenarios);
		}
		// 缺陷明细
		TestPlanReportBugExample bugExample = new TestPlanReportBugExample();
		bugExample.createCriteria().andTestPlanReportIdIn(ids);
		List<TestPlanReportBug> reportBugs = testPlanReportBugMapper.selectByExample(bugExample);
		if (CollectionUtils.isNotEmpty(reportBugs)) {
			groupSummary.setBugCount((long) reportBugs.size());
			reportBugs.forEach(reportBug -> {
				reportBug.setId(IDGenerator.nextStr());
				reportBug.setTestPlanReportId(reportId);
			});
			// 插入计划组关联用例缺陷数据
			TestPlanReportBugMapper batchMapper = sqlSession.getMapper(TestPlanReportBugMapper.class);
			batchMapper.batchInsert(reportBugs);
		}
		sqlSession.flushStatements();
		SqlSessionUtils.closeSqlSession(sqlSession, sqlSessionFactory);

		// 汇总并计算子报告执行的数据
		summaryExample.clear();
		summaryExample.createCriteria().andTestPlanReportIdIn(ids);
		List<TestPlanReportSummary> summaryList = testPlanReportSummaryMapper.selectByExampleWithBLOBs(summaryExample);
		List<CaseCount> functionalCaseCountList = new ArrayList<>();
		List<CaseCount> apiCaseCountList = new ArrayList<>();
		List<CaseCount> scenarioCountList = new ArrayList<>();
		List<CaseCount> executeCountList = new ArrayList<>();
		summaryList.forEach(summary -> {
			CaseCount functionalCaseCount = summary.getFunctionalExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(summary.getFunctionalExecuteResult()), CaseCount.class);
			CaseCount apiCaseCount = summary.getApiExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(summary.getApiExecuteResult()), CaseCount.class);
			CaseCount scenarioCount = summary.getScenarioExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(summary.getScenarioExecuteResult()), CaseCount.class);
			CaseCount executeCount = summary.getExecuteResult() == null ? CaseCount.builder().build() : JSON.parseObject(new String(summary.getExecuteResult()), CaseCount.class);
			functionalCaseCountList.add(functionalCaseCount);
			apiCaseCountList.add(apiCaseCount);
			scenarioCountList.add(scenarioCount);
			executeCountList.add(executeCount);
		});

		// 入库组汇总数据 => 报告详情表
		groupSummary.setFunctionalExecuteResult(JSON.toJSONBytes(CountUtils.summarizeProperties(functionalCaseCountList)));
		groupSummary.setApiExecuteResult(JSON.toJSONBytes(CountUtils.summarizeProperties(apiCaseCountList)));
		groupSummary.setScenarioExecuteResult(JSON.toJSONBytes(CountUtils.summarizeProperties(scenarioCountList)));
		groupSummary.setExecuteResult(JSON.toJSONBytes(CountUtils.summarizeProperties(executeCountList)));
		testPlanReportSummaryMapper.updateByPrimaryKeySelective(groupSummary);
	}

	/**
	 * 通过请求参数获取批量操作的ID集合
	 *
	 * @param request 请求参数
	 * @return ID集合
	 */
	public List<String> getBatchIds(TestPlanReportBatchRequest request) {
		if (request.isSelectAll()) {
			List<String> batchIds = extTestPlanReportMapper.getReportBatchIdsByParam(request);
			if (CollectionUtils.isNotEmpty(request.getExcludeIds())) {
				batchIds.removeIf(id -> request.getExcludeIds().contains(id));
			}
			return batchIds;
		} else {
			return request.getSelectIds();
		}
	}

	/**
	 * 校验计划是否存在
	 *
	 * @param planId 计划ID
	 * @return 测试计划
	 */
	private TestPlan checkPlan(String planId) {
		TestPlan testPlan = testPlanMapper.selectByPrimaryKey(planId);
		if (testPlan == null) {
			throw new MSException(Translator.get("test_plan_not_exist"));
		}
		return testPlan;
	}


	/**
	 * 校验报告是否存在
	 *
	 * @param id 报告ID
	 */
	private TestPlanReport checkReport(String id) {
		TestPlanReport testPlanReport = testPlanReportMapper.selectByPrimaryKey(id);
		if (testPlanReport == null) {
			throw new MSException(Translator.get("test_plan_report_not_exist"));
		}
		return testPlanReport;
	}

	/**
	 * 转存报告内容富文本临时文件
	 *
	 * @param reportId      报告ID
	 * @param projectId     项目ID
	 * @param uploadFileIds 上传的文件ID集合
	 * @param userId        用户ID
	 * @param source        文件来源
	 */
	private void transferRichTextTmpFile(String reportId, String projectId, List<String> uploadFileIds, String userId, String source) {
		if (CollectionUtils.isEmpty(uploadFileIds)) {
			return;
		}
		// 过滤已上传过的
		TestPlanReportAttachmentExample example = new TestPlanReportAttachmentExample();
		example.createCriteria().andTestPlanReportIdEqualTo(reportId).andFileIdIn(uploadFileIds).andSourceEqualTo(source);
		List<TestPlanReportAttachment> existReportMdFiles = testPlanReportAttachmentMapper.selectByExample(example);
		Map<String, TestPlanReportAttachment> existFileMap = existReportMdFiles.stream().collect(Collectors.toMap(TestPlanReportAttachment::getFileId, v -> v));
		List<String> fileIds = uploadFileIds.stream().filter(t -> !existFileMap.containsKey(t) && StringUtils.isNotBlank(t)).toList();
		if (CollectionUtils.isEmpty(fileIds)) {
			return;
		}
		// 处理本地上传文件
		FileRepository defaultRepository = FileCenter.getDefaultRepository();
		String systemTempDir = DefaultRepositoryDir.getSystemTempDir();
		// 添加文件与测试计划报告的关联关系
		Map<String, String> addFileMap = new HashMap<>(fileIds.size());
		LogUtils.info("开始上传富文本里的附件");
		List<TestPlanReportAttachment> attachments = fileIds.stream().map(fileId -> {
			TestPlanReportAttachment attachment = new TestPlanReportAttachment();
			String fileName = getTempFileNameByFileId(fileId);
			attachment.setId(IDGenerator.nextStr());
			attachment.setTestPlanReportId(reportId);
			attachment.setFileId(fileId);
			attachment.setFileName(fileName);
			attachment.setSource(source);
			long fileSize;
			try {
				FileCopyRequest fileCopyRequest = new FileCopyRequest();
				fileCopyRequest.setFolder(systemTempDir + "/" + fileId);
				fileCopyRequest.setFileName(fileName);
				fileSize = defaultRepository.getFileSize(fileCopyRequest);
			} catch (Exception e) {
				throw new MSException("读取富文本临时文件失败");
			}
			attachment.setSize(fileSize);
			attachment.setCreateUser(userId);
			attachment.setCreateTime(System.currentTimeMillis());
			addFileMap.put(fileId, fileName);
			return attachment;
		}).toList();
		testPlanReportAttachmentMapper.batchInsert(attachments);
		// 上传文件到对象存储
		LogUtils.info("upload to minio start");
		uploadFileResource(DefaultRepositoryDir.getPlanReportDir(projectId, reportId), addFileMap);
		LogUtils.info("upload to minio end");
	}

	/**
	 * 根据文件ID，查询MINIO中对应目录下的文件名称
	 */
	private String getTempFileNameByFileId(String fileId) {
		try {
			FileRequest fileRequest = new FileRequest();
			fileRequest.setFolder(DefaultRepositoryDir.getSystemTempDir() + "/" + fileId);
			List<String> folderFileNames = FileCenter.getDefaultRepository().getFolderFileNames(fileRequest);
			if (CollectionUtils.isEmpty(folderFileNames)) {
				return null;
			}
			String[] pathSplit = folderFileNames.getFirst().split("/");
			return pathSplit[pathSplit.length - 1];
		} catch (Exception e) {
			LogUtils.error(e);
			return null;
		}
	}

	/**
	 * 上传文件到资源目录
	 *
	 * @param folder     文件夹
	 * @param addFileMap 文件ID与文件名映射
	 */
	private void uploadFileResource(String folder, Map<String, String> addFileMap) {
		FileRepository defaultRepository = FileCenter.getDefaultRepository();
		for (String fileId : addFileMap.keySet()) {
			String systemTempDir = DefaultRepositoryDir.getSystemTempDir();
			try {
				String fileName = addFileMap.get(fileId);
				if (StringUtils.isEmpty(fileName)) {
					continue;
				}
				// 按ID建文件夹，避免文件名重复
				FileCopyRequest fileCopyRequest = new FileCopyRequest();
				fileCopyRequest.setCopyFolder(systemTempDir + "/" + fileId);
				fileCopyRequest.setCopyfileName(fileName);
				fileCopyRequest.setFileName(fileName);
				fileCopyRequest.setFolder(folder + "/" + fileId);
				// 将文件从临时目录复制到资源目录
				defaultRepository.copyFile(fileCopyRequest);
				// 删除临时文件
				fileCopyRequest.setFolder(systemTempDir + "/" + fileId);
				fileCopyRequest.setFileName(fileName);
				defaultRepository.delete(fileCopyRequest);
			} catch (Exception e) {
				LogUtils.error("上传富文本文件失败：{}", e);
				throw new MSException(Translator.get("file_upload_fail"));
			}
		}
	}

	/**
	 * 构建预生成报告的参数
	 *
	 * @param genRequest 报告请求参数
	 * @return 预生成报告参数
	 */
	private TestPlanReportGenPreParam buildReportGenParam(TestPlanReportGenRequest genRequest, TestPlan testPlan, String groupReportId) {
		TestPlanReportGenPreParam genPreParam = new TestPlanReportGenPreParam();
		BeanUtils.copyBean(genPreParam, genRequest);
		genPreParam.setTestPlanName(testPlan.getName());
		genPreParam.setStartTime(System.currentTimeMillis());
		// 报告预生成时, 执行状态为未执行, 结果状态为'-'
		genPreParam.setExecStatus(ExecStatus.PENDING.name());
		genPreParam.setResultStatus("-");
		// 是否集成报告(计划组报告), 目前根据是否计划组来区分
		genPreParam.setIntegrated(StringUtils.equals(testPlan.getType(), TestPlanConstants.TEST_PLAN_TYPE_GROUP));
		genPreParam.setGroupReportId(groupReportId);
		return genPreParam;
	}

	/**
	 * 统计执行状态的用例数量
	 *
	 * @param resultMapList 执行结果集合
	 * @return 用例数量
	 */
	private CaseCount countMap(List<CaseStatusCountMap> resultMapList) {
		CaseCount caseCount = new CaseCount();
		Map<String, Long> resultMap = resultMapList.stream().collect(Collectors.toMap(CaseStatusCountMap::getStatus, CaseStatusCountMap::getCount));
		caseCount.setSuccess(resultMap.getOrDefault(ResultStatus.SUCCESS.name(), 0L).intValue());
		caseCount.setError(resultMap.getOrDefault(ResultStatus.ERROR.name(), 0L).intValue());
		caseCount.setPending(resultMap.getOrDefault(ExecStatus.PENDING.name(), 0L).intValue() + resultMap.getOrDefault(ExecStatus.STOPPED.name(), 0L).intValue());
		caseCount.setBlock(resultMap.getOrDefault(ResultStatus.BLOCKED.name(), 0L).intValue());
		caseCount.setFakeError(resultMap.getOrDefault(ResultStatus.FAKE_ERROR.name(), 0L).intValue());
		return caseCount;
	}

	/**
	 * 获取组或者计划
	 *
	 * @param groupOrPlanId 计划组/报告ID
	 * @return 计划集合
	 */
	private List<TestPlan> getPlans(String groupOrPlanId) {
		TestPlan testPlan = checkPlan(groupOrPlanId);
		List<TestPlan> plans = new ArrayList<>();
		if (StringUtils.equals(testPlan.getType(), TestPlanConstants.TEST_PLAN_TYPE_GROUP)) {
			// 计划组
			TestPlanExample example = new TestPlanExample();
			example.createCriteria().andGroupIdEqualTo(groupOrPlanId);
			List<TestPlan> testPlans = testPlanMapper.selectByExample(example);
			plans.addAll(testPlans);
		}
		// 保证最后一条为计划组
		plans.addLast(testPlan);
		return plans;
	}

	/**
	 * 获取用户集合
	 *
	 * @param userIds 用户ID集合
	 * @return 用户集合
	 */
	private Map<String, String> getUserMap(List<String> userIds) {
		if (CollectionUtils.isEmpty(userIds)) {
			return new HashMap<>(16);
		}
		List<OptionDTO> userOptions = baseUserMapper.selectUserOptionByIds(userIds);
		return userOptions.stream().collect(Collectors.toMap(OptionDTO::getId, OptionDTO::getName));
	}

	/**
	 * 计划报告列表
	 *
	 * @param request 请求参数
	 * @return 报告列表
	 */
	public List<TestPlanReportDetailResponse> planReportList(TestPlanReportDetailPageRequest request) {
		return extTestPlanReportMapper.getPlanReportListById(request);
	}

	/**
	 * 计划报告测试集列表
	 * @param request 请求参数
	 * @param caseType 用例类型
	 * @return 测试集列表
	 */
	public List<TestPlanReportDetailCollectionResponse> listReportCollection(TestPlanReportDetailPageRequest request, String caseType) {
		List<TestPlanReportDetailCollectionResponse> collections;
		switch (caseType) {
			case CollectionQueryType.FUNCTIONAL -> collections = extTestPlanReportFunctionalCaseMapper.listCollection(request);
			case CollectionQueryType.API -> collections = extTestPlanReportApiCaseMapper.listCollection(request);
			case CollectionQueryType.SCENARIO -> collections = extTestPlanReportApiScenarioMapper.listCollection(request);
			default -> collections = new ArrayList<>();
		}
		return collections;
	}

	/**
	 * 预览富文本文件
	 *
	 * @param projectId  项目ID
	 * @param fileId     文件ID
	 * @param compressed 是否压缩
	 * @return 富文本内容
	 */
	public ResponseEntity<byte[]> previewMd(String projectId, String fileId, boolean compressed) {
		byte[] bytes;
		String fileName;
		TestPlanReportAttachmentExample example = new TestPlanReportAttachmentExample();
		example.createCriteria().andFileIdEqualTo(fileId);
		List<TestPlanReportAttachment> reportAttachments = testPlanReportAttachmentMapper.selectByExample(example);
		if (CollectionUtils.isEmpty(reportAttachments)) {
			// 在临时文件获取
			fileName = getTempFileNameByFileId(fileId);
			bytes = commonFileService.downloadTempImg(fileId, fileName, compressed);
		} else {
			// 在正式目录获取
			TestPlanReportAttachment attachment = reportAttachments.getFirst();
			fileName = attachment.getFileName();
			FileRequest fileRequest = buildPlanFileRequest(projectId, attachment.getTestPlanReportId(), attachment.getFileId(), attachment.getFileName());
			try {
				bytes = fileService.download(fileRequest);
			} catch (Exception e) {
				throw new MSException("get file error");
			}
		}
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("application/octet-stream"))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
				.body(bytes);
	}

	/**
	 * 更新执行时间和状态
	 *
	 * @param prepareReportId 预生成报告ID
	 */
	public void updateExecuteTimeAndStatus(String prepareReportId) {
		extTestPlanReportMapper.batchUpdateExecuteTimeAndStatus(System.currentTimeMillis(), Collections.singletonList(prepareReportId));
	}

	/**
	 * 构建计划报告文件请求
	 *
	 * @param projectId  项目ID
	 * @param resourceId 资源ID
	 * @param fileId     文件ID
	 * @param fileName   文件名称
	 * @return 文件请求对象
	 */
	private FileRequest buildPlanFileRequest(String projectId, String resourceId, String fileId, String fileName) {
		FileRequest fileRequest = new FileRequest();
		fileRequest.setFolder(DefaultRepositoryDir.getPlanReportDir(projectId, resourceId) + "/" + fileId);
		fileRequest.setFileName(StringUtils.isEmpty(fileName) ? null : fileName);
		fileRequest.setStorage(StorageType.MINIO.name());
		return fileRequest;
	}

	/**
	 * 获取模块集合
	 *
	 * @param moduleIds 模块ID集合
	 * @param caseType  用例类型
	 * @return 模块集合
	 */
	private Map<String, String> getModuleMapByIds(List<String> moduleIds, String caseType) {
		if (CollectionUtils.isEmpty(moduleIds)) {
			return Map.of();
		}
		List<TestPlanBaseModule> modules = new ArrayList<>();
		if (StringUtils.equals(caseType, CaseType.FUNCTIONAL_CASE.getKey())) {
			modules = extTestPlanReportFunctionalCaseMapper.getPlanExecuteCaseModules(moduleIds);
		} else if (StringUtils.equals(caseType, CaseType.API_CASE.getKey())) {
			modules = extTestPlanReportApiCaseMapper.getPlanExecuteCaseModules(moduleIds);
		} else if (StringUtils.equals(caseType, CaseType.SCENARIO_CASE.getKey())) {
			modules = extTestPlanReportApiScenarioMapper.getPlanExecuteCaseModules(moduleIds);
		}
		return modules.stream().collect(Collectors.toMap(TestPlanBaseModule::getId, TestPlanBaseModule::getName));
	}

	public void exportLog(String reportId, String userId, String projectId) {
		TestPlanReport testPlanReport = testPlanReportMapper.selectByPrimaryKey(reportId);
		Optional.ofNullable(testPlanReport).ifPresent(report -> testPlanReportLogService.exportLog(List.of(report), userId, projectId, "/test-plan/report/export/" + reportId));
	}

	public void batchExportLog(TestPlanReportBatchRequest request, String userId, String projectId) {
		List<String> reportIds = getBatchIds(request);
		if (CollectionUtils.isNotEmpty(reportIds)) {
			TestPlanReportExample example = new TestPlanReportExample();
			example.createCriteria().andIdIn(reportIds);
			List<TestPlanReport> reports = testPlanReportMapper.selectByExample(example);
			testPlanReportLogService.exportLog(reports, userId, projectId, "/test-plan/report/batch-export");
		}
	}
}
