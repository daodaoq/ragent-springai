# PyTorch 数据加载与训练实战

上一份教程介绍了 PyTorch 的基础概念，本教程深入讨论数据加载与训练工程化的细节。在人工智能实验室的日常工作中，数据管线的质量直接影响模型效果，而训练流程的组织方式决定了实验的可复现性和调试效率。本教程围绕 Dataset、DataLoader、数据增强、训练循环、学习率调度、断点续训和调试技巧展开，帮助你写出更稳健的训练代码。

## Dataset 抽象与实现

`torch.utils.data.Dataset` 是数据加载的基石，它定义了一套统一的接口：数据集应该知道自身有多少样本，并且能够按索引取出一条样本。

### Dataset 的两个核心方法

自定义数据集必须实现 `__len__` 和 `__getitem__` 两个方法。`__len__` 返回数据集长度，`__getitem__` 接收一个整数索引，返回该索引对应的样本。一个典型实现如下：

```python
from torch.utils.data import Dataset
import os
from PIL import Image

class ImageFolderDataset(Dataset):
    def __init__(self, image_paths, labels, transform=None):
        self.image_paths = image_paths
        self.labels = labels
        self.transform = transform

    def __len__(self):
        return len(self.image_paths)

    def __getitem__(self, idx):
        image = Image.open(self.image_paths[idx]).convert("RGB")
        label = self.labels[idx]
        if self.transform is not None:
            image = self.transform(image)
        return image, label
```

`__init__` 通常只负责记录路径和元数据，真正读取文件放在 `__getitem__` 里，这样配合 DataLoader 的多进程机制可以并行读取，而不是在初始化时一次性把所有数据读进内存。

### 为什么按需读取

许多数据集非常大，无法全部放进内存。把文件读取延迟到 `__getitem__` 执行时，可以让内存占用只与批次大小相关，而不是与整个数据集的大小相关。对于图像、音频等文件型数据，这是一种必要设计。

### 返回字典类型的数据集

在更复杂的任务里，一条样本可能包含多种输入，例如图像、文本和元信息。此时可以返回一个字典，DataLoader 会原样把它们组合成批次。配合自定义的批次处理函数，可以让数据管线更清晰。

### map 风格与 iterable 风格

PyTorch 提供两种数据集风格：map 风格实现 `__getitem__`，按索引随机访问，最常用；iterable 风格实现 `__iter__`，适合流式数据、无法随机访问的数据源。理解两者的区别有助于选择合适的接口。

## DataLoader 原理：批处理与打乱

`DataLoader` 在 Dataset 之上工作，核心职责是把单条样本组合成批次，并提供打乱、多进程加载等能力。理解它的行为对写出正确高效的训练循环非常重要。

### 批次采样过程

默认的采样器会生成一组索引，DataLoader 按这些索引从数据集中取样本，并把一个批次内的样本堆叠成张量。堆叠是否成功取决于样本形状是否一致，这也是维度不匹配错误的常见来源。

```python
from torch.utils.data import DataLoader

loader = DataLoader(dataset, batch_size=32, shuffle=True,
                    num_workers=4, drop_last=False)
```

### shuffle 打乱的作用

训练时开启 `shuffle` 能避免模型在固定的样本顺序上产生偏差，帮助随机梯度下降更快更稳地收敛。验证集和测试集不需要打乱，因为评估结果不应依赖顺序。`drop_last` 决定最后一个不足一个批次的尾批是否丢弃，某些模型或归一化层要求批次大小固定，此时可以开启。

### collate_fn 自定义批次组合

默认情况下，DataLoader 使用内置的堆叠逻辑把列表样本变成张量批次。当样本不是整齐的张量时，需要自定义 `collate_fn`。例如自然语言处理里，一个批次的句子长度不同，需要在 `collate_fn` 里做填充：

```python
def collate_batch(batch):
    images, labels = zip(*batch)
    images = torch.stack(images)
    labels = torch.tensor(labels)
    return images, labels
```

`collate_fn` 也常用来做动态 padding、过滤过长样本等操作，是数据管线里很灵活的一环。

### 批次大小与训练效果

批次大小是重要的超参数。大批次梯度估计更稳定、训练吞吐更高，但可能需要调大学习率；小批次有隐式的正则化效果，但训练速度较慢。实践中需要结合显存大小和收敛曲线综合选择，后续梯度累积小节会介绍如何用较小显存模拟大批次。

## DataLoader 多进程与 pin_memory

当数据读取成为训练瓶颈时，多进程加载和内存固定能显著提升效率。

### num_workers 的机制

`num_workers` 指定用多少个独立的子进程来预取数据。主进程拿到一个批次开始训练的同时，子进程在后台准备后续的批次，形成流水线，从而隐藏数据读取的时间。增加 `num_workers` 通常能提升吞吐，但子进程过多也会带来调度开销，需要实测找到最优值。

### 使用多进程的注意事项

- Windows 系统下，多进程数据加载要求数据加载相关代码放在 `if __name__ == "__main__"` 保护的代码块里，否则可能报错或产生递归进程。
- 数据集对象需要能被正确序列化传给子进程，避免在数据集里持有无法拷贝的资源，例如已打开的文件句柄或 GPU 张量。
- 调试时可以把 `num_workers` 设为 0，使用主进程加载，方便观察异常。

### pin_memory 的作用

`pin_memory=True` 会把数据放到页面锁定的内存中，这种内存在 CPU 到 GPU 的拷贝时使用更快的数据传输方式。配合 `to(device, non_blocking=True)` 使用，能让数据拷贝与计算重叠，进一步提升 GPU 利用率。在 CPU 训练时，`pin_memory` 没有明显收益。

### 数据加载的性能排查

如果 GPU 利用率很低而 CPU 占用很高，很可能是数据加载速度跟不上。常见的解决办法包括增大 `num_workers`、开启 `pin_memory`、减少 `__getitem__` 里的重复计算、把预处理结果缓存到磁盘或内存等。分析瓶颈时可以先分别测纯数据读取时间和纯计算时间，判断到底卡在哪一端。

## transform 预处理与 ToTensor、Normalize

`torchvision.transforms` 提供了一系列图像预处理操作，它们在样本进入模型之前完成格式转换和数值处理。

### Compose 组合多个变换

`transforms.Compose` 按顺序组合多个变换，前一个变换的输出作为后一个变换的输入。常见写法如下：

```python
from torchvision import transforms

transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize(mean=[0.485, 0.456, 0.406],
                         std=[0.229, 0.224, 0.225]),
])
```

### ToTensor 的职责

`ToTensor` 把 PIL 图像或 NumPy 数组转成浮点张量，并将像素值从 0 到 255 的整数范围缩放到 0 到 1 之间，同时把通道维度调整到最前面。图像数据通常以高、宽、通道的顺序存储，而 PyTorch 卷积层期望的是通道、高、宽的顺序，这一步转换是必不可少的。

### Normalize 标准化

`Normalize` 对每个通道执行减去均值再除以标准差的运算，把数据分布中心化，让每个通道都在相近的数值范围内。这样做有助于模型更快收敛，也避免某些通道数值过大主导梯度。均值和标准差可以取全局统计值，也可以根据数据集自行计算。

### 预处理放在数据管线哪里合适

预处理应放在 Dataset 内部或 transform 中，保证训练、验证、测试使用一致的流程。需要注意的是，训练时的数据增强与验证时的预处理通常不同，应该分别构造两套 transform 序列，验证时只做基本的格式转换和标准化。

## 数据增强管线

数据增强通过对训练样本施加随机变换，人为扩充数据的多样性，是缓解过拟合、提升模型泛化能力的重要手段。

### 常见的数据增强操作

下表列出若干常用的数据增强变换及其效果。

| 变换操作 | 说明 |
| --- | --- |
| RandomHorizontalFlip | 随机水平翻转，适合大部分图像任务 |
| RandomRotation | 随机旋转一定角度 |
| RandomCrop | 随机裁剪区域 |
| ColorJitter | 随机调整亮度、对比度、饱和度、色相 |
| RandomAffine | 随机仿射变换，包括平移、缩放、旋转 |
| GaussianBlur | 高斯模糊，模拟失焦效果 |
| RandomErasing | 随机遮挡部分区域，提升鲁棒性 |

### 增强的适用范围

数据增强的选择应贴合任务语义。例如对于猫狗分类，水平翻转是合理的，因为猫狗的照片左右翻转不改变类别；但对于数字识别或文字识别，水平翻转会改变语义，就不能盲目使用。旋转的角度上限也应根据任务设定，过大的旋转可能让模型学到错误的不变性。

### 训练与验证用不同的增强策略

一个常见的误区是训练和验证使用完全相同的 transform。正确做法是：训练时使用包含随机增强的完整管线，验证和测试时只使用确定的预处理，例如缩放、转张量、标准化。这样才能公平地评估模型在真实数据上的表现。

### 增强的随机性与可复现

数据增强引入随机性后，固定随机种子可以保证实验可复现。torchvision 的数据增强接受一个可选的 `rng` 参数，也可以在使用数据加载前调用 `torch.manual_seed` 设置全局种子，从而让每次实验的增强结果保持一致。

## 分布式采样与多卡训练的采样思想

当训练数据规模很大或者模型在多张 GPU 上并行训练时，数据加载也要相应调整，DistributedSampler 就是为这个场景设计的。

### 为什么要使用 DistributedSampler

在数据并行训练中，每张卡只处理整个数据集的一部分。如果没有合理的采样策略，可能会出现某些样本在多个进程中重复被取、而另一些样本从未被取到的情况。DistributedSampler 负责把数据集切分成互不重叠的若干份，分别交给不同的进程，并保证每个 epoch 的划分方式随打乱而变化。

### 基本用法

在多卡训练脚本中，通常先初始化分布式环境，然后创建 DistributedSampler 并传给 DataLoader，同时关闭 DataLoader 自带的 shuffle：

```python
from torch.utils.data.distributed import DistributedSampler

sampler = DistributedSampler(dataset, shuffle=True)
loader = DataLoader(dataset, batch_size=32, sampler=sampler)
```

因为 `shuffle` 逻辑由 sampler 负责，DataLoader 的 `shuffle` 参数应设置为 `False`。每个 epoch 开始时，还需要调用 `sampler.set_epoch(epoch)`，让打乱顺序在每个 epoch 都不同。

### 分布式训练的进阶概念

多卡训练还涉及梯度的跨卡同步，通常配合梯度 AllReduce 实现。使用 `torch.nn.parallel.DistributedDataParallel` 包装模型后，框架会自动处理梯度同步。理解 DistributedSampler 的采样思路，有助于阅读和维护大规模训练代码。

### 单机多卡与多机多卡

DistributedSampler 同时适用于单机多卡和多机多卡场景。单机多卡配置相对简单，多机则需要额外的网络通信配置。无论哪种方式，采样策略的思想是一致的：把全局数据均匀、互不重叠地分给所有参与的进程。

## 训练循环的标准写法

一个工程化的训练循环要比基础版复杂一些，它需要包含学习率调度、梯度裁剪、日志记录、验证等模块。

### 完整的训练流程骨架

下面是一个带有验证和早停判断的完整训练流程：

```python
def train(model, train_loader, val_loader, loss_fn, optimizer,
          scheduler, epochs, device, log_interval=100):
    best_acc = 0.0
    for epoch in range(1, epochs + 1):
        model.train()
        running_loss = 0.0
        for step, (x, y) in enumerate(train_loader, 1):
            x, y = x.to(device), y.to(device)

            optimizer.zero_grad()
            out = model(x)
            loss = loss_fn(out, y)
            loss.backward()
            optimizer.step()
            scheduler.step()  # 基于迭代步数的调度器

            running_loss += loss.item()
            if step % log_interval == 0:
                print(f"epoch {epoch} step {step} loss {running_loss/step:.4f}")

        val_acc = evaluate(model, val_loader, device)
        if val_acc > best_acc:
            best_acc = val_acc
            torch.save(model.state_dict(), "best_model.pth")
        print(f"epoch {epoch} val_acc {val_acc:.4f} best {best_acc:.4f}")
```

### 每个 epoch 的组织顺序

每个 epoch 通常先训练，再验证。训练前把模型切到训练模式，验证前切到评估模式。验证结果用来决定是否保存最优模型、是否降低学习率、是否提前停止。将验证从训练中独立出来，可以让每个 epoch 的评估口径保持一致。

### 训练循环里容易踩的坑

- 忘记调用 `model.train()` 或 `model.eval()`，导致 Dropout 和 BatchNorm 行为异常。
- 忘记调用 `optimizer.zero_grad()`，导致梯度累加，损失曲线异常。
- 把 `model.to(device)` 漏掉，导致设备不匹配。
- 在 `torch.no_grad` 块之外评估，浪费显存。

## 验证循环与测试循环

验证和测试虽然代码相似，但语义不同，组织方式也有讲究。

### 验证集与测试集的区分

验证集用于训练过程中挑选超参数和模型，测试集只在最终评估时使用一次，用来估计模型的真实泛化能力。测试集绝不参与任何调参决策，否则评估结果会失真。

### 验证循环的写法

验证循环通常在每个 epoch 之后执行，累积所有批次的损失和正确率，最后求平均。验证时使用 `model.eval()` 和 `torch.no_grad()`，如果每个 epoch 都做完整验证，可以只在一个子集上做快速验证以节省时间。

```python
def evaluate(model, loader, loss_fn, device):
    model.eval()
    total_loss, correct, total = 0.0, 0, 0
    with torch.no_grad():
        for x, y in loader:
            x, y = x.to(device), y.to(device)
            out = model(x)
            total_loss += loss_fn(out, y).item() * y.size(0)
            correct += (out.argmax(1) == y).sum().item()
            total += y.size(0)
    return total_loss / total, correct / total
```

### 测试循环的特殊之处

测试时除了准确率，还常常需要输出混淆矩阵、分类报告、置信度等更细致的评估指标。可以把预测和标签全部收集起来，测试结束后统一计算各类指标，这样既清晰又方便复用。

### 评估指标的选择

准确率只适合类别分布均衡的分类任务。对于不平衡数据集，应使用精确率、召回率、F1 值、AUC 等指标。指标的选择应与业务目标一致，例如医疗筛查更关注召回率，垃圾邮件过滤更关注精确率。

## 梯度累积模拟大 batch

当单卡显存放不下大的批次时，梯度累积可以在不增加显存占用的情况下，用多个小批次累加出近似大批次的梯度。

### 梯度累积的原理

PyTorch 的梯度默认是累加的。利用这一特性，连续执行若干个小批次的 `backward`，但只在累加了指定次数之后再执行一次 `optimizer.step()`，就相当于用这些批次的平均梯度更新一次参数。

```python
accumulation_steps = 4
optimizer.zero_grad()

for step, (x, y) in enumerate(train_loader):
    out = model(x.to(device))
    loss = loss_fn(out, y.to(device))
    loss = loss / accumulation_steps  # 归一化
    loss.backward()

    if (step + 1) % accumulation_steps == 0:
        optimizer.step()
        optimizer.zero_grad()
```

### 损失归一化的处理

因为多个批次累加梯度，为了避免梯度规模随累积步数增大，通常把每个小批次的损失除以累积步数，这样最终梯度近似于合并成一个大批次后的平均梯度。

### 梯度累积的适用场景

- 显存有限但希望使用较大的有效批次。
- 数据量较小时，希望减少参数更新的噪声。
- 配合学习率调度，模拟大批次训练的行为。

需要注意的是，BatchNorm 等依赖批次内统计量的层，在梯度累积下仍然使用每个小批次自己的统计量，与真正的大批次训练略有差异，效果上不能完全等价。

### 与学习率的关系

使用梯度累积模拟大批次时，可以相应地把学习率调大，但需要谨慎并观察验证集表现。大批次通常需要更大的学习率才能收敛到同样好的结果，这一关系可以通过实验标定。

## 学习率调度实战

学习率是训练中最重要的超参数之一。学习率调度器在训练过程中动态调整学习率，常见策略包括按 epoch 衰减、当验证指标停滞时降低、以及余弦退火等。

### 常用调度器对比

`torch.optim.lr_scheduler` 提供了多种现成的调度策略，下面表格对比几种常用调度器。

| 调度器 | 行为特点 | 适用场景 |
| --- | --- | --- |
| StepLR | 每隔固定步数将学习率乘一个系数 | 简单、可预期 |
| MultiStepLR | 在指定的若干 epoch 点降低学习率 | 已知收敛阶段 |
| ReduceLROnPlateau | 验证指标不再改善时自动降低学习率 | 通用、自适应 |
| CosineAnnealingLR | 学习率按余弦曲线周期性衰减 | 训练后期收敛精细 |
| OneCycleLR | 先升后降的单周期调度 | 快速收敛、节省训练时间 |

### ReduceLROnPlateau 的用法

`ReduceLROnPlateau` 需要接收验证指标，当指标连续 `patience` 个 epoch 没有改善时，就把学习率乘以 `factor`。它的 `step` 方法接受验证损失作为参数：

```python
scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
    optimizer, mode="min", factor=0.5, patience=3
)

for epoch in range(epochs):
    train_one_epoch(...)
    val_loss = validate(...)
    scheduler.step(val_loss)
```

### 余弦退火调度

`CosineAnnealingLR` 让学习率在 `T_max` 个 epoch 内从初始值平滑下降到接近 0，之后再重复下一个周期。配合 warmup（预热）使用，即在训练初期用小学习率逐渐升到目标值，可以避免训练一开始损失震荡。

### 调度器与优化器的配合

调度器读取优化器的 `lr` 并修改它，因此调度器要基于同一个优化器创建。不同调度器的 `step` 时机不同：按 epoch 的调度器在 epoch 结束时调用，按迭代步数的调度器在每个 step 后调用。使用错误会得到不正确的学习率曲线，务必阅读文档确认调用时机。

## 断点续训与 checkpoint

长时间训练不可避免会遇到中断，断点续训让训练可以从上次保存的状态继续，而不是从头再来。

### checkpoint 应保存的内容

一个完整的 checkpoint 至少包含模型参数、优化器状态、当前 epoch、学习率调度器状态，以及最好记录到的验证指标。这样恢复时不仅模型参数一致，优化器内部的动量和学习率历史也能还原。

```python
import torch

def save_checkpoint(model, optimizer, scheduler, epoch, best_acc, path):
    torch.save({
        "model": model.state_dict(),
        "optimizer": optimizer.state_dict(),
        "scheduler": scheduler.state_dict(),
        "epoch": epoch,
        "best_acc": best_acc,
    }, path)

def load_checkpoint(model, optimizer, scheduler, path, device):
    ckpt = torch.load(path, map_location=device)
    model.load_state_dict(ckpt["model"])
    optimizer.load_state_dict(ckpt["optimizer"])
    if scheduler is not None and "scheduler" in ckpt:
        scheduler.load_state_dict(ckpt["scheduler"])
    return ckpt["epoch"], ckpt["best_acc"]
```

### 恢复训练的流程

恢复后，训练循环应从保存的 `epoch + 1` 开始，并且把 `best_acc` 作为后续保存最优模型的基准。如果使用按 epoch 的学习率调度器，还需要恢复调度器状态，否则学习率会从初始状态重新开始，导致训练行为不一致。

### 断点续训的常见问题

- 模型结构必须与保存时的结构完全一致，否则加载 state_dict 会报键不匹配。
- 优化器状态加载要求优化器已经用相同参数创建好，再调用 `load_state_dict`。
- 保存和加载的设备要一致，或通过 `map_location` 指定设备。

### 定期保存与最优保存

实践中通常同时维护两类保存：定期保存最近一次的 checkpoint，用于断点续训；以及保存验证指标最优的模型，用于最终部署。最优模型应单独保存，避免被后续 epoch 覆盖。

## 日志记录与训练监控

训练过程的日志对于理解模型行为、对比实验至关重要。日志工具的选择取决于实验规模和个人习惯。

### 使用 print 的轻量记录

最简单的方式是周期性打印损失和准确率，配合 `tqdm` 可以显示进度条。轻量记录适合快速实验和调试，但不便于事后检索和分析大量历史数据。

### 使用 TensorBoard

TensorBoard 可以把标量、图像、模型结构等可视化。在 PyTorch 中通过 `torch.utils.tensorboard.SummaryWriter` 使用：

```python
from torch.utils.tensorboard import SummaryWriter

writer = SummaryWriter("runs/exp1")
# 每个 epoch 后记录
writer.add_scalar("train/loss", train_loss, epoch)
writer.add_scalar("val/acc", val_acc, epoch)
writer.add_histogram("fc1.weight", model.fc1.weight, epoch)
writer.close()
```

TensorBoard 可以同时对比多个实验，把不同实验写到不同的子目录，然后在同一面板里查看曲线。这样能直观比较学习率、批次大小、模型结构等因素的影响。

### 记录的内容建议

- 训练损失、验证损失、验证指标，至少每个 epoch 记录一次。
- 学习率的变化曲线，便于检查调度器是否按预期工作。
- 梯度范数，用于发现梯度爆炸或消失。
- 关键超参数和实验配置，保证实验可复现。

### 实验管理

对于大量的实验，建议建立统一的命名规则，把数据集、模型、超参数等关键信息编码到实验名或配置文件中。也可以使用专门的实验管理工具记录每次实验的配置、代码版本和结果，方便回溯和对比。

## 调试技巧

训练不收敛、损失异常、指标上不去等问题经常出现，掌握系统化的调试方法能大幅节省时间。

### 从过拟合单个 batch 开始

最常见的调试技巧是先用一个非常小的数据子集（例如一个 batch）训练，看模型能否记住这些样本。如果连单个 batch 都过拟合不了，说明模型、损失、优化器的基本链路有问题；如果能过拟合，说明基本机制正确，问题出在数据或超参数层面。

```python
one_batch = next(iter(train_loader))
for step in range(200):
    optimizer.zero_grad()
    loss = loss_fn(model(one_batch[0].to(device)),
                   one_batch[1].to(device))
    loss.backward()
    optimizer.step()
    print(step, loss.item())
```

### 检查输入与输出维度

在模型的关键位置打印张量形状，从数据进入模型到输出，逐步确认维度正确。把输入张量和模型期望的形状写下来对照，可以快速定位维度不匹配的问题。

### 检查梯度

如果训练不动，可以打印梯度的范数或逐参数的均值，判断梯度是否消失、爆炸或为空。梯度为空的参数说明它没有参与前向计算，需要检查模型结构。

```python
for name, param in model.named_parameters():
    if param.grad is not None:
        print(name, param.grad.norm().item())
```

### 检查数据与标签

数据错误是最隐蔽的问题。建议可视化几张样本，确认图像的形状、取值范围和标签是否正确对齐。标签错位、像素范围不对、通道顺序颠倒，都会导致训练异常，而且报错往往不在数据层。

### 复现性问题排查

如果发现不同次运行结果差异很大，检查是否固定了随机种子、是否使用了确定性算法、数据加载顺序是否一致。GPU 上的某些运算默认不是确定性的，必要时可以设置相关环境变量或 `torch.backends.cudnn.deterministic` 来获得可复现结果。

### 归纳调试清单

- 数据能正常取出、形状正确、标签对齐吗？
- 模型在单个 batch 上能过拟合吗？
- 损失在合理范围内吗？有没有出现 NaN？
- 梯度是否正常，有没有为空的参数？
- 训练和评估模式切换是否正确？
- 学习率和调度器是否按预期工作？

## 综合实战：完整的训练脚本

最后，把本教程的内容整合成一个结构完整的训练脚本骨架，供你在自己的项目上参考。

```python
import torch
import torch.nn as nn
import torch.optim as optim
from torch.optim import lr_scheduler
from torch.utils.data import DataLoader
from torch.utils.tensorboard import SummaryWriter
from torchvision import datasets, transforms

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

train_tf = transforms.Compose([
    transforms.RandomHorizontalFlip(),
    transforms.RandomRotation(10),
    transforms.ToTensor(),
    transforms.Normalize((0.5,), (0.5,)),
])
val_tf = transforms.Compose([
    transforms.ToTensor(),
    transforms.Normalize((0.5,), (0.5,)),
])

train_ds = datasets.MNIST("./data", train=True, transform=train_tf)
val_ds = datasets.MNIST("./data", train=False, transform=val_tf)
train_loader = DataLoader(train_ds, batch_size=64, shuffle=True)
val_loader = DataLoader(val_ds, batch_size=256, shuffle=False)

model = nn.Sequential(
    nn.Flatten(),
    nn.Linear(784, 256),
    nn.ReLU(),
    nn.Linear(256, 10),
).to(device)

optimizer = optim.Adam(model.parameters(), lr=1e-3)
scheduler = lr_scheduler.ReduceLROnPlateau(optimizer, factor=0.5, patience=2)
writer = SummaryWriter("runs/mnist_demo")

for epoch in range(10):
    model.train()
    for x, y in train_loader:
        x, y = x.to(device), y.to(device)
        optimizer.zero_grad()
        loss = nn.functional.cross_entropy(model(x), y)
        loss.backward()
        optimizer.step()

    model.eval()
    correct = total = 0
    with torch.no_grad():
        for x, y in val_loader:
            x, y = x.to(device), y.to(device)
            correct += (model(x).argmax(1) == y).sum().item()
            total += y.size(0)
    acc = correct / total

    scheduler.step(1 - acc)
    writer.add_scalar("val/acc", acc, epoch)
    print(f"epoch {epoch+1} acc {acc:.4f}")
```

这个脚本把数据增强、验证循环、学习率调度、日志记录组合在一起。实际项目中，你可以把数据加载、模型构建、训练、评估拆分成独立函数或模块，配以配置文件管理超参数，就能形成一套可复用的训练框架。

### 小结与工程实践建议

本教程系统介绍了数据加载与训练工程化的关键环节。数据管线的合理设计、训练循环的正确组织、超参数的系统调优和完备的日志体系，共同决定了深度学习项目的成败。

### 核心要点回顾

- Dataset 定义数据如何按索引读取，DataLoader 负责批次、打乱与并行。
- transform 负责预处理与数据增强，训练与验证应使用不同策略。
- 训练、验证、测试三个循环各自独立，注意模式和梯度开关。
- 梯度累积可以模拟大批次，学习率调度器和断点续训提升训练质量。
- 系统化调试：从过拟合单个 batch 开始，逐层检查维度与梯度。

### 推荐的实践流程

- 先搭建最小的完整流程，跑通一个 batch，再逐步增加复杂度。
- 每次只改动一个变量，保证实验结果可对比。
- 固定随机种子，保存完整 checkpoint，记录每次实验的配置。
- 训练过程中持续观察损失、验证指标和学习率曲线，发现异常及时干预。
- 把可复用的训练逻辑抽象成工具函数或模板，减少重复劳动。

数据加载与训练工程是深度学习中容易被低估却至关重要的部分。扎实掌握这些内容，可以让你把更多精力放在模型设计与问题建模上，做出更可靠的实验结果。
