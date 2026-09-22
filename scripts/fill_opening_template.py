from copy import copy
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Pt
from docx.oxml.ns import qn


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".data" / "reference-materials" / "thesis-template-source-20260922" / "converted" / "template_guidelines.docx"
OUTPUT = ROOT / "docs" / "08-course-deliverables" / "06_开题报告_按原模板_2026-09-22.docx"


def set_run_font(run, bold=False, size=10.5):
    run.font.name = "宋体"
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "宋体")
    run.font.size = Pt(size)
    run.bold = bold


def clear_cell(cell):
    cell.text = ""
    paragraph = cell.paragraphs[0]
    paragraph.alignment = WD_ALIGN_PARAGRAPH.LEFT
    paragraph.paragraph_format.space_before = Pt(0)
    paragraph.paragraph_format.space_after = Pt(0)
    paragraph.paragraph_format.line_spacing = 1.0
    return paragraph


def add_block(cell, blocks):
    """Replace cell text while keeping the original cell/table geometry."""
    first = clear_cell(cell)
    paragraphs = [first]
    for index, (text, bold) in enumerate(blocks):
        paragraph = first if index == 0 else cell.add_paragraph()
        paragraph.alignment = WD_ALIGN_PARAGRAPH.LEFT
        paragraph.paragraph_format.space_before = Pt(0)
        paragraph.paragraph_format.space_after = Pt(0)
        paragraph.paragraph_format.line_spacing = 1.0
        run = paragraph.add_run(text)
        set_run_font(run, bold=bold)
        paragraphs.append(paragraph)
    return paragraphs


def set_single(cell, text, bold=False, size=10.5):
    add_block(cell, [(text, bold)])
    run = cell.paragraphs[0].runs[0]
    set_run_font(run, bold=bold, size=size)


def main():
    if not SOURCE.exists():
        raise FileNotFoundError(SOURCE)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    document = Document(SOURCE)
    table = document.tables[0]

    # Basic information fields in the original template.
    set_single(table.cell(0, 1), "企业供应链数智化协同平台设计与开发")
    set_single(table.cell(1, 3), "软件工程")
    set_single(table.cell(1, 5), "23软工B班")
    set_single(table.cell(2, 1), "严绍诚")
    set_single(table.cell(2, 3), "2023030201071")
    set_single(table.cell(2, 5), "13702459393")
    set_single(table.cell(3, 1), "李文生")
    set_single(table.cell(3, 3), "教授")
    set_single(table.cell(3, 5), "计算机学院")

    # Section I: keep the template's three labels and write concise content.
    add_block(table.cell(5, 0), [
        ("一、选题背景及选题意义、国内外研究现状、初步设想及拟解决的问题：", True),
        ("选题背景及意义：制造业采购企业的 ERP、供应商协同系统和库存系统通常相互分散，需求、库存、采购订单、到货和对账信息难以连续追踪，容易出现预测滞后、延期到货和对账差异。本课题建设企业供应链数智化协同平台，形成“需求预测—采购协同—订单执行—收货入库—对账—异常复盘”闭环，提高数据一致性和业务可追溯性。", False),
        ("国内外研究现状：供应链管理研究已形成需求预测、库存控制、采购协同和供应商管理等方法体系，数据分析和机器学习被用于辅助计划制定；自然语言处理和大语言模型为采购变更、到货通知等文本的意图识别和实体抽取提供了新方法。现有系统仍普遍需要解决多源数据口径不一、跨环节证据不足以及模型输出难以审计的问题。", False),
        ("初步设想及拟解决的问题：采用 Vue 3、Spring Boot 模块化单体和 FastAPI 构建前后端分离平台，以 CSV 模拟 ERP、SRM 和库存数据接入；使用可解释的 14 日预测和采购情景推演辅助计划，使用本地 Qwen 模型完成受限文本解析，并通过规则校验、人工确认、权限控制和操作日志防止模型越权。重点解决数据融合、业务闭环、异常预警和敏感数据本地处理问题。", False),
    ])

    # Section II: methods and means.
    add_block(table.cell(6, 0), [
        ("二、论文撰写过程中拟采取的方法和手段：", True),
        ("1. 文献研究法：检索供应链协同、需求预测、自然语言处理和企业信息系统相关文献，归纳研究现状。", False),
        ("2. 需求分析法：从采购人员、管理者、供应商和管理员等角色提炼业务流程、功能需求、权限边界和非功能需求。", False),
        ("3. 系统设计与迭代开发法：采用 B/S、前后端分离和模块化单体架构，完成数据库、接口、状态机和页面设计。", False),
        ("4. 混合智能方法：由预测模型完成数值计算，由规则完成状态和风险判断，由本地大语言模型完成白名单文本解析和解释辅助，结果必须经过校验和确认。", False),
        ("5. 实验与测试法：通过接口测试、集成测试、浏览器场景测试、数据库验收和用户 UAT 验证系统功能、可靠性和安全边界。", False),
    ])

    # Section III: outline, retaining the template wording and adding a compact two-level outline.
    add_block(table.cell(7, 0), [
        ("三、论文（设计）提纲：", True),
        ("第1章 绪论", True),
        ("1.1 研究背景与意义    1.2 国内外研究现状    1.3 研究内容与组织结构", False),
        ("第2章 相关技术与理论基础", True),
        ("2.1 前后端分离与模块化单体    2.2 供应链数据融合与需求预测    2.3 本地大语言模型与安全边界", False),
        ("第3章 系统需求分析", True),
        ("3.1 用户角色与业务流程    3.2 功能需求    3.3 非功能需求", False),
        ("第4章 系统设计", True),
        ("4.1 总体架构    4.2 数据库设计    4.3 业务状态与接口设计    4.4 AI 安全与审计设计", False),
        ("第5章 系统实现与测试", True),
        ("5.1 数据导入与预测    5.2 采购协同与履约    5.3 预警、对账与经营分析    5.4 AI 解析与测试结果", False),
        ("第6章 总结与展望", True),
    ])

    # Section IV: retain the original six-line schedule shape.
    add_block(table.cell(8, 0), [
        ("四、计划进度", True),
        ("2026.09.15-2026.10.15，毕业设计（论文）调研、查阅文献及完成任务书和开题报告", False),
        ("2026.10.16-2026.11.15，毕业设计（论文）需求分析、系统设计和数据库设计", False),
        ("2026.11.16-2027.02.28，完成采购协同、预测、收货、对账和预警等功能开发", False),
        ("2027.03.01-2027.03.31，完成系统联调、测试、用户验收和问题修复", False),
        ("2027.04.01-2027.04.30，完成论文撰写、定稿和答辩准备", False),
        ("2027.05.01-2027.05.10，完成论文终稿、系统验收和材料提交", False),
    ])

    add_block(table.cell(9, 0), [
        ("五、参考文献", True),
        ("[1] Chopra S. Supply Chain Management: Strategy, Planning, and Operation[M]. Pearson, 2019.", False),
        ("[2] Vaswani A, et al. Attention Is All You Need[C]. NeurIPS, 2017.", False),
        ("[3] 马士华, 林勇. 供应链管理（第5版）[M]. 北京: 机械工业出版社, 2016.", False),
        ("[4] 李航. 统计学习方法（第2版）[M]. 北京: 清华大学出版社, 2019.", False),
        ("[5] 周志华. 机器学习[M]. 北京: 机械工业出版社, 2020.", False),
    ])

    # Avoid changing the original template file; only save the filled copy.
    document.save(OUTPUT)
    print(OUTPUT)


if __name__ == "__main__":
    main()

