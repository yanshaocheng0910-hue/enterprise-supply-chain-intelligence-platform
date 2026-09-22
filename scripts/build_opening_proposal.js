const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, WidthType, BorderStyle, ShadingType, HeadingLevel,
  Header, Footer, PageNumber, PageBreak
} = require('../.cache/docx/node_modules/docx');

const projectRoot = path.resolve(__dirname, '..');
const outputDir = path.join(projectRoot, 'docs', '08-course-deliverables');
const docxPath = path.join(outputDir, '06_开题报告_工作稿_2026-09-22.docx');
const mdPath = path.join(outputDir, '06_开题报告_工作稿_2026-09-22.md');

const title = '企业供应链数智化协同平台设计与开发';
const bodyFont = '宋体';
const headingFont = '黑体';
const contentWidth = 8506;
const tableBorder = { style: BorderStyle.SINGLE, size: 4, color: 'B7B7B7' };
const borders = { top: tableBorder, bottom: tableBorder, left: tableBorder, right: tableBorder };
const cellMargins = { top: 90, bottom: 90, left: 130, right: 130 };

const data = {
  background: [
    '制造业采购企业通常同时使用 ERP、供应商协同系统和库存管理系统，需求、库存、采购订单、到货和对账信息分散在不同数据源中。数据口径不一致、计划变更依赖人工传递、订单执行状态难以连续追踪，容易造成库存不足、延期到货和对账差异。',
    '近年来，供应链数智化逐渐从单一业务系统建设转向跨环节协同和数据驱动决策。需求预测可以为采购计划提供量化依据，自然语言处理可以帮助识别采购变更意图，大语言模型也可以辅助解释异常和生成协同建议。但供应链和财务数据具有敏感性，直接依赖外部云端模型会带来数据外发、权限边界和结果不可控等风险。',
    '本课题以企业采购供应链为对象，建设一个可在本地运行的 B/S 数智化协同平台，将多源数据导入、需求预测、采购协同、履约跟踪、收货入库、对账、异常预警和受控 AI 能力组织为可追溯闭环。'
  ],
  significance: [
    '实践意义：为采购企业提供从需求、计划、订单到收货和对账的统一操作入口，降低跨系统复制和人工追踪成本，并通过批次、审计和履约时间线保留业务证据。',
    '工程意义：验证 Vue 3 前端、Spring Boot 模块化单体、独立 FastAPI 智能服务和关系数据库的协同方式，在不引入不必要微服务的前提下兼顾业务事务、算法实验和本地部署。',
    '研究意义：研究确定性业务规则、时间序列预测和本地大语言模型之间的边界，将模型限制在结构化解析、风险排序和解释辅助范围，并通过人工确认、规则校验和审计记录提高系统可解释性与可复现性。'
  ],
  status: [
    '供应链管理研究强调需求、库存、采购、交付和供应商之间的协同优化；数据分析和预测方法可以改善计划制定，但实际应用仍依赖统一的数据模型和稳定的业务流程。',
    '自然语言处理和 Transformer 类模型为采购变更、到货通知等非结构化文本的意图识别和实体抽取提供了新的实现路径。对于企业系统而言，模型输出还必须经过结构校验、业务事实校验和人工确认，不能直接绕过权限和状态机修改单据。',
    '现有企业平台常见问题是业务模块能够独立 CRUD，但预测、协同、执行、对账和复盘之间缺少连续证据链。本课题的研究重点不是堆叠模型数量，而是把数据融合、预测建议、协同执行和审计复盘放在同一闭环中，并在本地部署条件下验证安全边界。',
    '目前已完成可运行候选版的主要工程基础：Vue 3 前端、Spring Boot 业务服务、FastAPI 智能服务、Flyway 迁移、H2/MySQL 适配、本地 Qwen2.5-1.5B 推理、14 日预测、采购情景推演、角色待办、经营分析和订单履约时间线均已有实现或运行证据。正式开题后的重点是完成用户 UAT、V6 数据库复验、Compose 复现、扩大实验集并按学校格式整理材料。'
  ],
  problems: [
    '多源供应链数据字段、编码、时间和来源不统一，难以直接支撑预测与业务联动。',
    '采购需求、采购计划、订单、到货、入库和对账往往分散在不同页面，异常发生后缺少完整的责任和证据链。',
    '自然语言需求和计划变更包含物料、数量、日期、供应商和原因等实体，人工录入效率低；模型直接写入业务又存在幻觉、越权和不可审计风险。',
    '供应链和财务数据不能默认发送到外部 API，需要设计本地模型、规则降级和数据最小化传递机制。',
    '系统不仅要展示功能，还要能通过接口测试、浏览器验证、数据库验收和操作日志说明功能确实生效。'
  ],
  contents: [
    ['多源数据导入与统一模型', '支持供应商、物料、库存和需求历史等 CSV 数据的文件选择、预览、字段校验、提交、错误明细和批次追踪，并保留来源系统字段，模拟 ERP、SRM 与库存系统的数据接入。'],
    ['需求预测与采购情景推演', '基于历史需求和库存事实形成 14 日预测；以移动平均作为可解释基线，在数据条件满足时选择 XGBoost；对需求波动、供应延迟、安全库存、合格率和价格变化进行情景模拟，模拟结果经确认后才生成采购需求。'],
    ['采购协同业务闭环', '实现采购需求、采购计划、采购订单、供应商确认、发货/到货预通知、分批收货、验收、入库和对账等业务状态流转，明确角色边界、幂等约束和异常状态。'],
    ['供应链监测与异常预警', '根据库存安全线、订单延期、供应商交期、到货数量、验收和对账差异生成预警，并通过待办中心、经营分析报告和订单履约时间线提供处置入口与复盘证据。'],
    ['本地 AI 解析与辅助分析', '仅开放采购需求、计划变更和到货通知等白名单意图；由本地 Qwen2.5-1.5B 按固定 Schema 解析文本，系统再次执行字段、权限、状态和数据库事实校验；模型不可用时显式降级到规则结果。'],
    ['权限、安全与审计', '采用 JWT 登录、四类角色权限、供应商数据隔离、账号停用后旧令牌失效、操作日志和 AI 分析快照，确保 FastAPI 不直接访问业务数据库，业务写入统一由 Spring Boot 完成。'],
    ['测试与证据归档', '建立需求—接口—数据表—测试—截图—论文小节的追踪关系，执行后端、AI、前端构建、运行健康、MySQL 和浏览器验证，并保留失败、降级和待复验记录。']
  ],
  methods: [
    ['文献研究法', '围绕供应链协同、需求预测、自然语言处理、企业信息系统和软件工程质量开展文献检索，形成研究现状和技术选型依据。'],
    ['需求分析法', '从采购企业、采购人员、管理者、供应商和管理员等角色出发，提炼业务用例、状态机、权限约束、非功能需求和数据安全边界。'],
    ['系统设计与迭代开发法', '采用 B/S、前后端分离和模块化单体架构，Vue 负责交互，Spring Boot 负责业务事务和数据写入，FastAPI 负责无状态预测与智能解析，按可运行版本迭代。'],
    ['混合智能方法', '用统计/机器学习模型完成数值预测，用规则完成安全库存、状态和风险判断，用本地大语言模型完成受限文本结构化与解释排序，输出必须经过校验和人工确认。'],
    ['实验与验收法', '使用合成演示数据和可复现测试集，结合单元/集成测试、接口测试、浏览器场景测试、数据库一致性验收和用户 UAT，对准确性、可靠性、安全边界和可操作性进行评价。']
  ],
  expected: [
    '形成可在 D 盘本地运行的企业供应链数智化协同平台候选版本，完成前端、业务后端、智能服务、数据库迁移和运行说明。',
    '形成“需求预测—采购需求—计划—订单—供应商确认—到货—验收—入库—对账—异常复盘”的可演示闭环，并支持角色权限和供应商数据隔离。',
    '形成多源 CSV 导入、14 日预测、采购情景推演、异常预警、角色待办、经营分析、订单履约证据链和本地 AI 解析等功能的实现与测试记录。',
    '形成需求规格说明书、功能列表、开发设计说明书、测试用例与测试报告、用户使用手册、实训报告、论文初稿和答辩演示所需的事实材料。',
    '完成用户 UAT、V6 MySQL 复验、Docker Compose 复现和更大 Ground Truth 集评测后，再冻结 V1.0 结论，不把当前候选版描述为生产系统。'
  ],
  innovations: [
    ['可追溯的供应链闭环', '将数据导入批次、预测建议、采购单据、收货/入库、对账、预警、待办和操作日志串成同一条可核验履约证据链，减少“页面完成但无法复盘”的问题。'],
    ['受控的本地 AI 边界', '将大语言模型限制在白名单意图解析、风险排序和解释辅助；精确金额、数量、状态和业务动作由确定性规则与事务服务负责，模型不可用时有明确规则降级。'],
    ['人在回路的可审计确认', 'AI 结果先展示原文、结构化字段、来源和校验信息，再由用户确认落库；保存 provider、model、prompt、原始输出、规范化结果、回退原因和数据指纹，便于审计和论文实验复现。'],
    ['面向敏感数据的便携部署', '采用模块化单体加独立智能服务的轻量架构，模型、缓存和日志可全部放在本地 D 盘，默认禁止把供应链/财务文本发送到非本机地址，兼顾企业安全和毕业设计可演示性。']
  ],
  schedule: [
    ['2026.09—2026.10', '文献调研、需求梳理、开题报告、总体架构、数据库模型和数据导入设计', '平台候选版已有基础；完成开题材料、研究问题确认和论文证据清单'],
    ['2026.10—2026.12', '用户权限、供应商、物料、库存、采购需求、采购计划和采购订单模块完善', '完成跨角色 UAT 第一轮，补齐状态边界和异常提示'],
    ['2026.12—2027.02', '供应商协同、发货/到货预通知、收货验收、入库、对账、看板和异常预警完善', '完成履约闭环、待办中心和时间线证据复核'],
    ['2027.02—2027.03', '需求预测、计划变更解析、本地 AI、情景推演、经营分析和性能优化', '扩大 Ground Truth，统计准确率、降级率和人工修改率'],
    ['2027.03—2027.04', '系统测试、MySQL V6 复验、Compose 复现、用户 UAT、问题修复和论文初稿', '冻结主要截图、测试报告和论文实验数据'],
    ['2027.04—2027.05', '论文修改、格式检查、最终材料整理、答辩 PPT 和演示彩排', '完成导师审阅、最终验收、提交和答辩准备']
  ],
  references: [
    'Chopra S. Supply Chain Management: Strategy, Planning, and Operation[M]. Pearson, 2019.',
    'Vaswani A, Shazeer N, Parmar N, et al. Attention Is All You Need[C]. NeurIPS, 2017.',
    '马士华, 林勇. 供应链管理（第5版）[M]. 北京: 机械工业出版社, 2016.',
    '李航. 统计学习方法（第2版）[M]. 北京: 清华大学出版社, 2019.',
    '周志华. 机器学习[M]. 北京: 清华大学出版社, 2016.',
    'Min H. Artificial intelligence in supply chain management: theory and applications[J]. International Journal of Logistics Research and Applications, 2010, 13(1): 13-39.',
    'Waller M A, Fawcett S E. Data science, predictive analytics, and big data: a revolution that will transform supply chain design and management[J]. Journal of Business Logistics, 2013, 34(2): 77-84.',
    'Hevner A R, March S T, Park J, et al. Design science in information systems research[J]. MIS Quarterly, 2004, 28(1): 75-105.',
    'Fowler M. Patterns of Enterprise Application Architecture[M]. Boston: Addison-Wesley, 2002.',
    '电子科技大学中山学院计算机学院. 计算机学院毕业论文范文及撰写规范[Z]. 2026.'
  ]
};

function run(text, options = {}) {
  return new TextRun({ text, font: options.font || bodyFont, size: options.size || 24, bold: options.bold || false, color: options.color });
}

function para(text = '', options = {}) {
  return new Paragraph({
    alignment: options.alignment || AlignmentType.JUSTIFIED,
    spacing: { before: options.before || 0, after: options.after || 120, line: 300 },
    indent: options.indent === false ? undefined : { firstLine: 480 },
    pageBreakBefore: options.pageBreakBefore || false,
    children: [run(text, options)]
  });
}

function heading(text, level = 1) {
  return new Paragraph({
    heading: level === 1 ? HeadingLevel.HEADING_1 : HeadingLevel.HEADING_2,
    spacing: { before: level === 1 ? 260 : 160, after: 120, line: 300 },
    children: [run(text, { font: headingFont, size: level === 1 ? 28 : 26, bold: true })]
  });
}

function cell(text, width, options = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders,
    margins: cellMargins,
    shading: options.shading ? { fill: options.shading, type: ShadingType.CLEAR } : undefined,
    children: [new Paragraph({
      alignment: options.alignment || AlignmentType.LEFT,
      spacing: { after: 0, line: 280 },
      children: [run(text, { bold: options.bold || false, font: options.font || bodyFont, size: options.size || 22 })]
    })]
  });
}

function table(rows, widths, header = false) {
  return new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: rows.map((row, rowIndex) => new TableRow({
      children: row.map((value, colIndex) => cell(value, widths[colIndex], { bold: header && rowIndex === 0, shading: header && rowIndex === 0 ? 'EDEDED' : undefined }))
    }))
  });
}

function numbered(items) {
  return items.map((item, index) => para(`${index + 1}. ${item}`, { indent: false }));
}

function labelled(items) {
  return items.flatMap(([label, text]) => [
    new Paragraph({ spacing: { before: 80, after: 60, line: 300 }, indent: { firstLine: 480 }, children: [run(label + '：', { bold: true }), run(text)] })
  ]);
}

function mdContent() {
  const lines = [
    `# ${title}`,
    '',
    '## 开题报告（工作稿）',
    '',
    '> 版本：2026-09-22；依据学校开题报告模板与当前项目真实实现整理。正式提交前需由导师确认题目、研究现状、参考文献和时间安排。',
    '',
    '| 项目 | 内容 |',
    '|---|---|',
    '| 学生 | 严绍诚 |',
    '| 学号 | 2023030201071 |',
    '| 专业班级 | 23软件B班 |',
    '| 指导教师 | 李文生；张璐（以正式表单为准） |',
    '| 课题类型 | 工程设计、产品开发 |',
    '| 课题来源 | 社会生产实践 |',
    '| 设计周期 | 2026年9月1日—2027年5月10日 |',
    '',
    '## 一、选题背景、研究意义与问题分析',
    '',
    '### （一）选题背景',
    ...data.background,
    '',
    '### （二）研究意义',
    ...data.significance,
    '',
    '### （三）国内外研究现状（初步）',
    ...data.status,
    '',
    '### （四）拟解决的主要问题',
    ...data.problems.map((x, i) => `${i + 1}. ${x}`),
    '',
    '## 二、研究内容',
    ...data.contents.map(([a, b]) => `### ${a}\n${b}`),
    '',
    '## 三、研究方法与技术路线',
    ...data.methods.map(([a, b]) => `### ${a}\n${b}`),
    '',
    '技术路线：多源数据导入与标准化 → 需求预测与情景推演 → 采购需求与计划 → 订单协同与履约 → 收货入库与对账 → 异常预警与经营分析 → 本地 AI 解析/解释 → 测试、审计和复盘。',
    '',
    '## 四、预期结果与拟解决的关键工程问题',
    ...data.expected.map((x, i) => `${i + 1}. ${x}`),
    '',
    '## 五、拟形成的工程创新点',
    ...data.innovations.map(([a, b]) => `### ${a}\n${b}`),
    '',
    '## 六、进度安排',
    '| 时间 | 主要工作 | 阶段产出 |',
    '|---|---|---|',
    ...data.schedule.map(row => `| ${row[0]} | ${row[1]} | ${row[2]} |`),
    '',
    '## 七、参考文献（初步）',
    ...data.references.map((x, i) => `[${i + 1}] ${x}`),
    '',
    '## 八、待导师确认与后续补充',
    '1. 正式检索并补充满足学校要求的参考文献数量、英文文献数量和近三年文献数量。',
    '2. 根据导师意见调整研究现状、创新点和工作量边界；不将本地候选版直接表述为生产系统。',
    '3. 完成用户 UAT、V6 MySQL 复验、Docker Compose 复现和扩展实验后，更新预期成果中的实际数据和论文证据。'
  ];
  return lines.join('\n');
}

async function build() {
  fs.mkdirSync(outputDir, { recursive: true });
  fs.writeFileSync(mdPath, mdContent(), 'utf8');

  const children = [];
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 80 }, children: [run(title, { font: headingFont, size: 34, bold: true })] }));
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 80 }, children: [run('本科毕业设计（论文）开题报告（工作稿）', { font: headingFont, size: 28, bold: true })] }));
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 220 }, children: [run('版本：2026年9月22日｜依据学校模板与当前项目真实状态整理', { size: 20, color: '666666' })] }));

  const infoRows = [
    ['学生姓名', '严绍诚', '学号', '2023030201071'],
    ['专业班级', '23软件B班', '指导教师', '李文生；张璐（以正式表单为准）'],
    ['课题类型', '工程设计、产品开发', '课题来源', '社会生产实践'],
    ['设计周期', '2026年9月1日—2027年5月10日', '所属学院', '计算机学院'],
    ['课题名称', title, '', '']
  ];
  children.push(table(infoRows, [1500, 2850, 1500, 2656]));
  children.push(para('说明：本文件为开题报告工作稿，不覆盖学校原始模板。当前项目已经有可运行候选版，但用户 UAT、V6 MySQL 复验、Docker Compose 复现和正式参考文献扩充仍属于后续工作。', { before: 180, after: 220, indent: false, size: 21, color: '666666' }));

  children.push(heading('一、选题背景、研究意义与问题分析'));
  children.push(heading('（一）选题背景', 2));
  children.push(...data.background.map(x => para(x)));
  children.push(heading('（二）研究意义', 2));
  children.push(...data.significance.map(x => para(x)));
  children.push(heading('（三）国内外研究现状（初步）', 2));
  children.push(...data.status.map(x => para(x)));
  children.push(heading('（四）拟解决的主要问题', 2));
  children.push(...numbered(data.problems));

  children.push(heading('二、研究内容'));
  children.push(...labelled(data.contents));

  children.push(heading('三、研究方法与技术路线'));
  children.push(...labelled(data.methods));
  children.push(para('技术路线：多源数据导入与标准化 → 需求预测与情景推演 → 采购需求与计划 → 订单协同与履约 → 收货入库与对账 → 异常预警与经营分析 → 本地 AI 解析/解释 → 测试、审计和复盘。', { before: 120 }));

  children.push(heading('四、预期结果与拟解决的关键工程问题'));
  children.push(...numbered(data.expected));

  children.push(heading('五、拟形成的工程创新点'));
  children.push(...labelled(data.innovations));

  children.push(heading('六、进度安排'));
  children.push(table([['时间', '主要工作', '阶段产出'], ...data.schedule], [1500, 4100, 2906], true));

  children.push(heading('七、参考文献（初步）'));
  children.push(...data.references.map((x, i) => para(`[${i + 1}] ${x}`, { indent: false, size: 22 })));
  children.push(para('注：以上为开题阶段初步参考文献。正式论文将按学校要求继续补充并核验参考文献数量、英文文献比例、近三年文献比例和引用顺序。', { indent: false, size: 21, color: '666666' }));

  children.push(heading('八、待导师确认与后续补充'));
  children.push(...numbered([
    '正式检索并补充满足学校要求的参考文献数量、英文文献数量和近三年文献数量。',
    '根据导师意见调整研究现状、创新点和工作量边界；不将本地候选版直接表述为生产系统。',
    '完成用户 UAT、V6 MySQL 复验、Docker Compose 复现和扩展实验后，更新预期成果中的实际数据和论文证据。'
  ]));

  children.push(new Paragraph({ pageBreakBefore: true, spacing: { after: 140 }, children: [run('导师意见：', { font: headingFont, size: 26, bold: true })] }));
  children.push(para('（请导师填写）', { indent: false, color: '888888' }));
  for (let i = 0; i < 6; i++) children.push(para('________________________________________________________________________________', { indent: false, color: '999999', size: 20 }));
  children.push(para('导师签字：________________    日期：______年____月____日', { indent: false, before: 100 }));
  children.push(new Paragraph({ spacing: { before: 360, after: 140 }, children: [run('学院审核意见：', { font: headingFont, size: 26, bold: true })] }));
  for (let i = 0; i < 5; i++) children.push(para('________________________________________________________________________________', { indent: false, color: '999999', size: 20 }));
  children.push(para('审核人签字：________________    日期：______年____月____日', { indent: false, before: 100 }));

  const doc = new Document({
    creator: 'Codex',
    title: `${title}——开题报告工作稿`,
    description: '基于学校模板和项目真实状态生成的开题报告工作稿',
    styles: {
      default: { document: { run: { font: bodyFont, size: 24 }, paragraph: { spacing: { line: 300 } } } },
      paragraphStyles: [
        { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { font: headingFont, size: 28, bold: true }, paragraph: { spacing: { before: 260, after: 120, line: 300 }, outlineLevel: 0 } },
        { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { font: headingFont, size: 26, bold: true }, paragraph: { spacing: { before: 160, after: 120, line: 300 }, outlineLevel: 1 } }
      ]
    },
    sections: [{
      properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1440, right: 1700, bottom: 1440, left: 1700 } } },
      headers: { default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [run('企业供应链数智化协同平台｜开题报告工作稿', { size: 18, color: '888888' })] })] }) },
      footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [run('第 ', { size: 18, color: '888888' }), new TextRun({ children: [PageNumber.CURRENT], font: bodyFont, size: 18, color: '888888' }), run(' 页', { size: 18, color: '888888' })] })] }) },
      children
    }]
  });
  const buffer = await Packer.toBuffer(doc);
  fs.writeFileSync(docxPath, buffer);
  console.log(docxPath);
  console.log(mdPath);
}

build().catch(error => { console.error(error); process.exit(1); });

