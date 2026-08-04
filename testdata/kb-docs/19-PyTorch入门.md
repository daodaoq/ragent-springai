# PyTorch 入门教程

PyTorch 是当前人工智能实验室中最常用的深度学习框架之一，它兼具灵活的动态计算图和直观的 Python 编程体验。本教程面向初次接触深度学习框架的同学，从张量、自动求导出发，逐步讲解如何用 PyTorch 构建、训练和评估一个神经网络模型。整篇教程将贯穿一个手写数字分类器的例子，把每个抽象概念都落到可运行的代码上。

## 张量 Tensor 的基础操作

张量是 PyTorch 中一切计算的基本单位，可以把它理解成一个可以放在 GPU 上运算的多维数组。零维张量是标量，一维张量是向量，二维张量是矩阵，三维及以上的张量则是更高维的数据容器。图像通常用四维张量表示，形状为批次大小、通道数、高、宽四个维度。

### 创建张量的几种方式

最直接的创建方式是从 Python 列表转换，也可以使用各种工厂函数生成特定形态的张量。下面代码展示了常用的创建方法：

```python
import torch

# 从列表创建
a = torch.tensor([[1, 2], [3, 4]])

# 全零张量
b = torch.zeros(3, 4)

# 全一张量
c = torch.ones(2, 2)

# 单位矩阵
d = torch.eye(3)

# 均匀分布随机张量
e = torch.rand(2, 3)

# 标准正态分布随机张量
f = torch.randn(2, 3)

# 与某张量形状相同的张量
g = torch.zeros_like(b)
```

创建时需要注意 `torch.tensor` 与 `torch.Tensor` 的区别。`torch.tensor` 会根据传入数据推断数据类型，而 `torch.Tensor` 默认创建浮点张量。实际开发中建议显式指定数据类型，例如 `torch.tensor([1, 2], dtype=torch.float32)`，这样能避免类型不匹配导致的报错。

### 张量的基本属性

每个张量都有几个常用属性：`shape` 表示形状，`dtype` 表示数据类型，`device` 表示所在设备。查看这些属性的方法如下：

```python
x = torch.randn(4, 3)
print(x.shape)    # torch.Size([4, 3])
print(x.dtype)    # torch.float32
print(x.device)   # cpu
print(x.numel())  # 元素总数 12
```

`numel` 返回张量中所有元素的个数，在计算参数量或展平特征时很常用。例如模型某个特征图形状是批次、通道、高、宽，想把它展平成一维，就可以用 `reshape` 方法。

### 张量的索引与切片

张量支持类似 NumPy 的索引和切片语法，包括整数索引、范围切片、布尔掩码等。下面是一些常见的用法：

```python
x = torch.arange(12).reshape(3, 4)
print(x[0])        # 第一行
print(x[1:, :2])   # 从第二行起，取前两列
print(x[x > 5])    # 布尔掩码取大于 5 的元素
```

需要强调的是，PyTorch 张量的索引操作通常返回视图而不是副本，也就是底层内存是共享的。如果后续对视图做原地修改，会影响原始张量。若想得到独立的副本，应显式调用 `clone` 方法。

## 张量运算进阶

张量之间的运算分为逐元素运算、矩阵运算和归约运算三类，它们构成了神经网络前向传播的基本零件。

### 逐元素运算

加减乘除、幂运算、开方、三角函数等都是逐元素运算，两个张量需要满足广播规则才能直接计算。广播的规则是：从尾部维度开始比较，两个维度相等、或其中一个为 1、或其中一个缺失时才能对齐。例如形状为批次、特征的张量可以与形状为特征的一维张量相加，后者会自动扩展。

```python
a = torch.randn(2, 3)
b = torch.tensor([1.0, 2.0, 3.0])
c = a + b  # b 广播到 (2, 3)
```

### 矩阵乘法

矩阵乘法用 `matmul` 方法，或者直接用 `@` 运算符。矩阵乘法要求第一个张量的最后一维与第二个张量的倒数第二维相等。对于带批次的高维张量，`matmul` 会自动做批量矩阵乘法，非常适合处理一批样本同时过网络的情形。

```python
x = torch.randn(4, 16)
w = torch.randn(16, 8)
out = x @ w  # 结果是 (4, 8)
```

### 归约运算

`sum`、`mean`、`max`、`min`、`std` 等是常见的归约运算。它们可以指定沿哪个维度归约，并可通过 `keepdim` 参数保留维度，这在后面做 softmax 或归一化时特别重要。

```python
x = torch.randn(4, 8)
row_sum = x.sum(dim=1, keepdim=True)  # 形状保持 (4, 1)
```

归约运算经常配合广播实现去均值、标准化等操作。例如计算每个样本的均值并减去，就可以利用 `keepdim` 保住对齐所需的维度。

### 原地运算与自动求导的注意事项

以 `_` 结尾的方法名表示原地运算，比如 `add_`、`mul_`、`zero_`。原地运算会节省内存，但如果一个张量同时被用于自动求导的计算图，原地修改可能破坏梯度计算，导致反向传播报错或梯度不正确。因此，除非能确认张量不参与梯度计算，否则建议用返回新张量的版本。

## 自动求导 autograd 与梯度

自动求导是 PyTorch 区别于传统编程最核心的特性。只要张量设置了 `requires_grad=True`，PyTorch 就会在前向计算过程中动态记录每一步运算，构建一张计算图，随后调用 `backward` 就能自动算出所有叶子张量的梯度。

### 计算图与叶子张量

在自动求导机制里，我们称最初创建的、不是由其他张量运算得到的张量为叶子张量。模型的参数通常就是叶子张量。中间的运算结果被称为非叶子张量，它们也会有 `grad` 属性，但默认不保留梯度以节省内存。

```python
x = torch.tensor([2.0], requires_grad=True)
y = x ** 2 + 3 * x
y.backward()
print(x.grad)  # tensor([7.])，即 2*x+3 在 x=2 处的值
```

上面的例子中，函数是 y 等于 x 的平方加 3x，对 x 求导的结果是 2x 加 3，代入 x 等于 2 得到 7，这与打印结果一致。

### 停止梯度追踪

有些时候我们不想让某些计算参与求导，例如在评估模型时、做特征提取时，或者使用预训练模型做推理时。有三种常见方式：

- 用 `torch.no_grad()` 上下文管理器包裹代码，退出后自动恢复。
- 调用张量的 `detach` 方法，返回一个不参与计算图的新张量。
- 对张量设置 `requires_grad=False`。

```python
with torch.no_grad():
    z = model(x)  # 不构建计算图，节省内存和计算

y = model(x).detach()  # 分离出不需要梯度的张量
```

### 梯度累加与清零

默认情况下，PyTorch 不会自动清空梯度。每次调用 `backward`，梯度会累加到之前的梯度上。这在实现梯度累积、或者处理特殊计算图时是需要的特性，但也容易导致初学者困惑：如果不手动清零，多次反向传播得到的梯度会越积越大。因此训练循环里通常在每次更新前调用 `optimizer.zero_grad()`。

### 手动计算梯度与验证

在调试时，可以用 `torch.autograd.grad` 手动获取某个张量对参数的梯度，并与解析公式对照，以此验证自己的模型或自定义层是否正确。这是排查梯度问题时非常有效的办法，后面调试技巧章节还会再提到。

## 构建模型：nn.Module 与 nn.Linear

用 PyTorch 组织模型的标准方式是把模型定义成 `nn.Module` 的子类。`nn.Module` 提供参数管理、设备迁移、保存加载、训练评估切换等基础设施，我们只需实现前向传播 `forward` 方法。

### 自定义模型的骨架

一个最简单的线性模型长这样：

```python
import torch.nn as nn

class MyLinear(nn.Module):
    def __init__(self, in_features, out_features):
        super().__init__()
        self.weight = nn.Parameter(torch.randn(in_features, out_features))
        self.bias = nn.Parameter(torch.zeros(out_features))

    def forward(self, x):
        return x @ self.weight + self.bias
```

在 `__init__` 中把需要学习的参数包装成 `nn.Parameter`，这样它们就会被自动注册到模型的 `parameters()` 迭代器中，优化器才能更新它们。

### 使用 nn.Linear 快速构建全连接层

不过通常我们不需要手写线性层，`nn.Linear` 已经封装好了权重和偏置，并做了合适的初始化。下面是一个两层全连接网络：

```python
class MLP(nn.Module):
    def __init__(self, in_dim=784, hidden_dim=128, out_dim=10):
        super().__init__()
        self.fc1 = nn.Linear(in_dim, hidden_dim)
        self.relu = nn.ReLU()
        self.fc2 = nn.Linear(hidden_dim, out_dim)

    def forward(self, x):
        x = self.relu(self.fc1(x))
        return self.fc2(x)
```

注意 `forward` 里不要显式调用 `model(x)` 之外的方法来触发前向，PyTorch 约定统一通过调用模型对象本身来执行前向传播。

### 模块的层级组织与子模块

`nn.Module` 支持任意嵌套。你可以在一个模块的 `__init__` 里创建其他模块作为子模块，调用 `model.parameters()` 时会把所有子模块的参数一起收集起来，`model.to(device)` 也会递归地迁移全部子模块。这保证了大规模网络也能用统一的接口管理。

### 查看模型结构与参数量

`print(model)` 可以打印模型的结构层次。计算参数量可以遍历参数统计 `numel`，也可以用第三方工具展示每一层的参数个数。了解模型参数量有助于判断模型规模是否合理，以及是否可能过拟合。

## nn.Sequential 与更复杂的网络

对于层数较多且没有复杂分支的网络，可以用 `nn.Sequential` 把层按顺序拼接起来，省去手写 `forward` 的样板代码。

```python
model = nn.Sequential(
    nn.Flatten(),
    nn.Linear(784, 256),
    nn.ReLU(),
    nn.Linear(256, 10),
)
```

`nn.Sequential` 内部会按列表顺序执行每一层，前一个模块的输出作为后一个模块的输入。它非常适合搭建结构规整的全连接网络或者简单的卷积栈。

### 使用 OrderedDict 为层命名

当需要按名字访问某一层时，可以给 `nn.Sequential` 传入一个有序字典，这样每层都有了可读的名称，便于打印和后续加载部分参数。

```python
from collections import OrderedDict

model = nn.Sequential(OrderedDict([
    ("fc1", nn.Linear(784, 256)),
    ("act", nn.ReLU()),
    ("fc2", nn.Linear(256, 10)),
]))
```

### 常用层类型一览

除了全连接层，PyTorch 还提供了大量常用层，下面表格列出几种常见类型及其适用场景。

| 层类型 | 典型用途 | 说明 |
| --- | --- | --- |
| nn.Conv2d | 图像特征提取 | 卷积核在空间上滑动，减少参数量 |
| nn.BatchNorm2d | 稳定训练 | 对特征图做批归一化，加速收敛 |
| nn.MaxPool2d | 下采样 | 取局部最大值，缩小特征图尺寸 |
| nn.Dropout | 缓解过拟合 | 训练时随机丢弃部分神经元 |
| nn.Embedding | 词向量查找 | 把离散的索引映射为稠密向量 |
| nn.LSTM / nn.GRU | 序列建模 | 处理时间序列或文本序列 |
| nn.TransformerEncoder | 注意力建模 | 堆叠自注意力层处理序列 |

选择层时主要依据数据形态和任务类型。图像任务优先考虑卷积结构，序列任务优先考虑循环或注意力结构，表格型数据则常用全连接结构。

### 自定义前向传播的灵活场景

当模型有残差连接、多分支输入或者动态条件分支时，`nn.Sequential` 就不够用了，此时应该回到继承 `nn.Module` 手动写 `forward` 的方式。手动写前向传播没有额外限制，你可以自由地在层与层之间插入任何张量运算。

## 定义损失函数与优化器

训练的目标是让损失函数尽量小，损失函数衡量了模型预测与真实标签之间的差距，优化器则负责根据梯度更新模型参数。

### 常见损失函数

分类任务最常用交叉熵损失，PyTorch 中对应 `nn.CrossEntropyLoss`。它把全连接层输出的原始 logits 与类别索引直接作为输入，内部已经包含了 softmax 操作，所以不需要在模型里手动加 softmax。均方误差 `nn.MSELoss` 常用于回归任务，`nn.BCEWithLogitsLoss` 常用于多标签或二分类任务。

| 损失函数 | 适用任务 | 说明 |
| --- | --- | --- |
| CrossEntropyLoss | 多分类 | 输入 logits 和类别索引，内部含 softmax |
| MSELoss | 回归 | 输出与目标之间的均方误差 |
| L1Loss | 回归 | 平均绝对误差，对离群点更稳健 |
| BCEWithLogitsLoss | 二分类/多标签 | 内部含 sigmoid，数值更稳定 |
| KLDivLoss | 分布拟合 | 常用于知识蒸馏等场景 |

### 选择优化器

随机梯度下降是最基础的优化器，可以通过动量加速收敛；Adam 则是实际中最常用的优化器，它结合了动量与自适应学习率，对学习率的选取不那么敏感，适合作为初学者的默认选择。`AdamW` 在 Adam 基础上修正了权重衰减的处理方式，配合 Transformer 类模型效果更好。

```python
import torch.optim as optim

model = MLP()

# 随机梯度下降，带动量
optimizer = optim.SGD(model.parameters(), lr=0.01, momentum=0.9)

# Adam 优化器
optimizer = optim.Adam(model.parameters(), lr=1e-3)
```

### 损失函数与优化器的关联

损失函数和优化器都只与模型参数挂钩，两者之间没有直接的绑定关系。优化器只需要知道要更新哪些参数，损失函数只需要接收预测和标签计算一个标量。在训练循环里，我们先用损失函数计算标量损失，再调用优化器的 `step` 更新参数。

### 权重衰减与正则化

在优化器里可以设置 `weight_decay` 参数实现 L2 正则化，它的作用是让参数值不过分变大，从而缓解过拟合。实际调参时，`weight_decay` 通常设置在很小的数量级，例如万分之一到千分之一，具体数值需要根据验证集表现来调整。

## 标准训练循环写法

把前面的概念组合起来，就得到了深度学习训练的标准范式。一个训练循环可以拆成五个固定步骤：清零梯度、前向计算、计算损失、反向传播、更新参数。

```python
def train_one_epoch(model, dataloader, loss_fn, optimizer, device):
    model.train()
    total_loss = 0.0
    correct = 0
    total = 0
    for x, y in dataloader:
        x, y = x.to(device), y.to(device)

        optimizer.zero_grad()      # 第一步：清零梯度
        logits = model(x)          # 第二步：前向计算
        loss = loss_fn(logits, y)  # 第三步：计算损失
        loss.backward()            # 第四步：反向传播
        optimizer.step()           # 第五步：更新参数

        total_loss += loss.item() * x.size(0)
        preds = logits.argmax(dim=1)
        correct += (preds == y).sum().item()
        total += y.size(0)
    return total_loss / total, correct / total
```

### 每一步的目的

清零梯度是必要的，因为梯度默认会累加。前向计算得到模型的输出 logits，损失函数把 logits 与真实标签比较得到标量损失。反向传播基于链式法则为每个参数计算出梯度，最后优化器沿负梯度方向更新参数。

### 训练模式与批处理

训练前调用 `model.train()` 会开启训练模式，影响 Dropout、BatchNorm 等层的行为。数据按批次从 DataLoader 取出，每个批次内的样本并行计算，这样可以充分利用 GPU 的并行能力，也使得梯度是对一个批次样本的近似平均。

### 监控训练过程

训练循环中通常会累积本轮的损失和准确率，打印或记录到日志里。观察损失曲线是否稳定下降，可以帮助判断学习率是否合适、模型是否在正常收敛。如果损失震荡剧烈，可能需要降低学习率；如果损失长期不下降，则需要检查数据或模型结构。

## GPU 迁移与 cuda 判断

把计算放到 GPU 上是深度学习中加速训练的主要手段。PyTorch 提供了一套统一接口，让代码能同时在 CPU 和 GPU 上运行。

### 设备检测的惯用写法

最常用的写法是先判断当前环境是否有可用的 CUDA GPU，然后确定设备变量，统一用 `to(device)` 迁移模型和数据：

```python
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
model = model.to(device)
```

在 Apple 的 M 系列芯片上，还可以考虑使用 `torch.backends.mps.is_available()` 判断是否可用 MPS 设备。不过是否使用要视库的兼容性而定，某些算子可能尚未支持。

### 数据与模型的设备一致性

一个最常见的问题就是模型在 GPU 上、而数据还在 CPU 上，或者模型和数据在不同 GPU 上，运行时就会抛出设备不匹配的报错。养成在取到数据后立刻 `x = x.to(device)` 的习惯，能避免大量此类问题。注意模型只需要迁移一次，而每个批次的数据每次都要迁移。

### 使用 GPU 的注意事项

- 显存是有限资源，较大的批次会占用更多显存，超出后会报显存不足的错误。
- `model.to(device)` 是在原地修改模型的参数设备，不是返回新模型。
- 在 `torch.no_grad` 下推理时同样要把输入迁移到模型所在的设备。
- 多进程数据加载与 GPU 数据迁移顺序要合理，避免在子进程里复制 GPU 张量造成开销。

## 保存与加载模型

训练得到的模型参数需要持久化保存，这样可以在之后继续训练、做推理或部署。PyTorch 提供了灵活的保存机制。

### 保存 state_dict

推荐的做法是只保存模型的参数字典 `state_dict`，它保存的是从参数名到张量的映射。这样文件小、格式清晰，并且与模型结构解耦。

```python
torch.save(model.state_dict(), "model_weights.pth")
```

### 加载 state_dict

加载时先创建与保存时结构一致的模型，再用 `load_state_dict` 把参数填进去：

```python
model = MLP()
state = torch.load("model_weights.pth", map_location="cpu")
model.load_state_dict(state)
model = model.to(device)
```

`map_location` 参数用于指定加载到哪个设备。当保存时在 GPU 上、加载时没有 GPU，或者反过来，显式指定 `map_location` 可以避免设备不匹配。

### 保存完整模型

`torch.save(model, "model.pth")` 会保存整个模型对象，包括结构和参数，加载时直接 `torch.load` 就能得到一个可用的模型。这种方式加载方便，但可移植性较差，一旦类定义发生变化就可能加载失败，因此通常只用于快速实验，正式项目推荐用 state_dict 方式。

### 保存优化器状态

如果需要断点续训，除了模型参数，还要保存优化器的状态，因为优化器内部维护着动量、自适应学习率等历史信息。常见做法是打包成一个字典：

```python
checkpoint = {
    "model_state": model.state_dict(),
    "optimizer_state": optimizer.state_dict(),
    "epoch": epoch,
    "best_acc": best_acc,
}
torch.save(checkpoint, "checkpoint.pth")
```

## Dataset 与 DataLoader 的使用

数据以数据集和数据加载器的形式喂给模型。`Dataset` 定义如何按索引取出一条数据，`DataLoader` 负责把多条数据组成批次并提供打乱、并行加载等能力。

### 自定义 Dataset

自定义数据集需要继承 `torch.utils.data.Dataset` 并实现两个方法：`__len__` 返回样本总数，`__getitem__` 根据索引返回样本和标签的元组。

```python
from torch.utils.data import Dataset

class MyDataset(Dataset):
    def __init__(self, data, labels):
        self.data = data
        self.labels = labels

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        return self.data[idx], self.labels[idx]
```

### 使用 DataLoader

`DataLoader` 在数据集之上提供批次、打乱和多进程加载。核心参数包括 `batch_size`、`shuffle`、`num_workers`、`pin_memory`。

```python
from torch.utils.data import DataLoader

loader = DataLoader(dataset, batch_size=64, shuffle=True,
                    num_workers=4, pin_memory=True)
```

训练时 `shuffle` 设置为真可以让每个 epoch 样本顺序不同，避免模型记住固定的顺序；验证和测试时不需要打乱。`num_workers` 控制用几个子进程预取数据，`pin_memory` 开启后能加快 CPU 到 GPU 的数据拷贝。

### 遍历 DataLoader

`DataLoader` 是可迭代对象，每次迭代返回一个批次。默认情况下批次是列表，如果设置了 collate 函数或者数据集返回的是字典，迭代出来的结构会相应变化。配合 `enumerate` 可以拿到批次序号，用于周期性的日志打印。

## 模型评估模式与推理

训练结束后要对模型做评估，评估时的写法与训练时有显著差异，核心是关闭梯度追踪并切换到评估模式。

### eval 模式的作用

`model.eval()` 把模型切到评估模式，这会改变那些行为依赖模式的层：Dropout 不再随机丢弃神经元，BatchNorm 使用累积的统计量而不是当前批次的统计量。忘记调用 `eval` 是评估结果不稳定的常见原因。

### 配合 no_grad 推理

推理时用 `torch.no_grad()` 包裹，避免为每个中间结果保存计算图，从而大幅降低显存占用并加快速度。评估时通常不需要反向传播，所以可以安全地关闭梯度。

```python
def evaluate(model, dataloader, loss_fn, device):
    model.eval()
    total_loss = 0.0
    correct = 0
    total = 0
    with torch.no_grad():
        for x, y in dataloader:
            x, y = x.to(device), y.to(device)
            logits = model(x)
            loss = loss_fn(logits, y)
            total_loss += loss.item() * x.size(0)
            preds = logits.argmax(dim=1)
            correct += (preds == y).sum().item()
            total += y.size(0)
    return total_loss / total, correct / total
```

### 训练模式与评估模式的来回切换

在训练循环里每个 epoch 开头要调用 `model.train()`，评估时调用 `model.eval()`。这两个方法会递归作用于所有子模块。如果在训练和评估之间来回切换时忘了恢复训练模式，模型后续训练会因为 Dropout 没生效或 BatchNorm 统计量不更新而出现异常。

## 常见报错排查

初学者在编写 PyTorch 代码时经常会遇到几类报错，这里总结最常见的几种及其排查思路。

### 维度不匹配

报错信息形如尺寸不匹配、矩阵乘法维度错误。这类问题通常是因为输入张量没有展平、某一层输入维度计算错误，或者数据集的输出与模型期望的输入不一致。排查方法是在每一层前后打印张量的 `shape`，从数据进入模型开始逐层核对。

### 梯度为 None

当某参数的 `grad` 是 `None` 时，通常意味着该参数没有参与前向计算，导致反向传播没有给它产生梯度。常见原因包括：参数没有在 `forward` 中被使用，条件分支导致某些参数在某些批次里未被用到，或者使用了 `detach` 切断了路径。检查方法是对不更新的参数单独调用 `retain_grad` 或手动验证。

### CUDA 相关报错

显存不足会直接报 CUDA 内存溢出，此时应该减小 `batch_size`，减少中间变量的保留，或者用 `torch.no_grad` 关闭不需要的梯度。设备不匹配的报错则要检查模型和数据是否都在同一个设备上。

### 张量设备与类型混淆

把整数张量和浮点张量混用、或者把 CPU 张量直接传给 GPU 上的模型，都会触发类型或设备错误。养成习惯：输入模型前统一 `to(device)`，必要时用 `float()` 或 `long()` 转换类型。

### 反向传播报错

如果出现反向传播相关报错，先看是否对非叶子张量求了梯度，是否在计算图中做了原地修改，或者是否有 `NaN` 出现在损失里。损失出现 `NaN` 常常源于学习率过大、数据存在异常值或梯度爆炸，可以从降低学习率、检查输入数据、查看梯度范数几个方向排查。

## 从零训练一个手写数字分类器

下面把前面所有知识串成一个完整的、可以直接运行的例子。我们使用经典的手写数字数据集，构建一个多层感知机完成十分类任务。

### 准备数据与模型

```python
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader
from torchvision import datasets, transforms

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

transform = transforms.Compose([
    transforms.ToTensor(),
    transforms.Normalize((0.1307,), (0.3081,)),
])

train_data = datasets.MNIST(root="./data", train=True,
                            download=True, transform=transform)
test_data = datasets.MNIST(root="./data", train=False,
                           download=True, transform=transform)

train_loader = DataLoader(train_data, batch_size=128, shuffle=True)
test_loader = DataLoader(test_data, batch_size=256, shuffle=False)

class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Flatten(),
            nn.Linear(28 * 28, 256),
            nn.ReLU(),
            nn.Linear(256, 128),
            nn.ReLU(),
            nn.Linear(128, 10),
        )

    def forward(self, x):
        return self.net(x)

model = Net().to(device)
loss_fn = nn.CrossEntropyLoss()
optimizer = optim.Adam(model.parameters(), lr=1e-3)
```

### 训练与评估

```python
def train_one_epoch(model, loader, loss_fn, optimizer, device):
    model.train()
    for x, y in loader:
        x, y = x.to(device), y.to(device)
        optimizer.zero_grad()
        loss = loss_fn(model(x), y)
        loss.backward()
        optimizer.step()

def evaluate(model, loader, device):
    model.eval()
    correct = 0
    total = 0
    with torch.no_grad():
        for x, y in loader:
            x, y = x.to(device), y.to(device)
            preds = model(x).argmax(dim=1)
            correct += (preds == y).sum().item()
            total += y.size(0)
    return correct / total

for epoch in range(10):
    train_one_epoch(model, train_loader, loss_fn, optimizer, device)
    acc = evaluate(model, test_loader, device)
    print(f"epoch {epoch+1}, test acc {acc:.4f}")
```

### 对示例的解读

这个例子覆盖了本教程的所有要点：通过 `transforms` 对图像做预处理，通过 `Dataset` 与 `DataLoader` 组织数据批次，通过 `nn.Module` 组织网络结构，用交叉熵作为损失，用 Adam 作为优化器，训练循环里依次执行清零梯度、前向、损失、反向、更新，评估时切换到 `eval` 模式并关闭梯度追踪。把这个例子跑通，并理解每一行的作用，就基本掌握了 PyTorch 的核心用法。

## 总结与进阶建议

本教程介绍了 PyTorch 入门阶段最重要的知识：张量操作、自动求导、模块化建模、损失与优化器、训练循环、GPU 使用、模型保存加载、数据加载与评估。这些内容构成了用 PyTorch 完成一个深度学习任务的最小闭环。

### 进阶学习方向

- 卷积神经网络与图像分类的常用结构。
- 循环神经网络与 Transformer 在序列任务中的应用。
- 迁移学习：加载预训练模型并微调。
- 分布式训练与混合精度训练。
- 模型部署与性能优化。

### 常见实践建议

- 从小模型、小数据开始调试，确认逻辑正确后再扩大规模。
- 固定随机种子，保证实验可复现。
- 每次修改代码后重新验证数据加载和形状是否正常。
- 及时记录实验配置和结果，方便对比调参。
- 使用版本管理工具保存代码，用固定路径存放数据集和模型文件。

持续在真实问题上练习，把报错当作学习机会，是掌握 PyTorch 最有效的方式。希望这份教程能成为你深度学习之旅的坚实起点。
