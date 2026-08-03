# PyTorch 环境搭建指南

## 安装 CUDA 工具链
实验室服务器建议安装 CUDA 12.1 及以上版本，显卡驱动版本需不低于 525。可通过 nvidia-smi 命令检查驱动是否正常。

## 创建虚拟环境
使用 conda 创建 Python 3.10 的独立环境，避免污染系统 Python：
conda create -n torch python=3.10
conda activate torch

## 安装 PyTorch
务必从官方源安装 CUDA 版本，否则 GPU 不可用：
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121

## 验证 GPU 可用
python -c "import torch; print(torch.cuda.is_available())"
输出 True 表示 CUDA 加速生效；输出 False 则需要检查驱动版本与安装源是否匹配。

## 常见问题
显存不足时可设置环境变量 PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True 缓解显存碎片化。
多卡训练建议使用 torchrun 启动，示例：torchrun --nproc_per_node=2 train.py
