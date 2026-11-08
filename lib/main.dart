import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart' as pdf;
import 'package:syncfusion_flutter_pdfviewer/pdfviewer.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'PDF Reader Pro',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
        home: const HomePage(),
      );
}

// ---------------- লাইব্রেরি (শেষ পড়া পেজ সংরক্ষণ) ----------------

class Book {
  String path, name;
  int page, total;
  Book(this.path, this.name, {this.page = 1, this.total = 0});
  Map<String, dynamic> toJson() =>
      {'path': path, 'name': name, 'page': page, 'total': total};
  factory Book.fromJson(Map<String, dynamic> j) =>
      Book(j['path'], j['name'], page: j['page'] ?? 1, total: j['total'] ?? 0);
}

class Store {
  static Future<List<Book>> load() async {
    final p = await SharedPreferences.getInstance();
    final s = p.getString('books');
    if (s == null) return [];
    return (jsonDecode(s) as List)
        .map((e) => Book.fromJson(e))
        .where((b) => File(b.path).existsSync())
        .toList();
  }

  static Future<void> save(List<Book> l) async {
    final p = await SharedPreferences.getInstance();
    await p.setString('books', jsonEncode(l.map((e) => e.toJson()).toList()));
  }

  static Future<void> updatePage(String path, int page, int total) async {
    final l = await load();
    for (final b in l) {
      if (b.path == path) {
        b.page = page;
        if (total > 0) b.total = total;
      }
    }
    await save(l);
  }
}

// ---------------- হোম ----------------

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  List<Book> _books = [];

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final l = await Store.load();
    if (mounted) setState(() => _books = l);
  }

  Future<void> _pick() async {
    final r = await FilePicker.platform
        .pickFiles(type: FileType.custom, allowedExtensions: ['pdf']);
    if (r == null || r.files.single.path == null) return;
    final dir = await getApplicationDocumentsDirectory();
    final folder = Directory('${dir.path}/books');
    await folder.create(recursive: true);
    final name = r.files.single.name;
    final dest = File(
        '${folder.path}/${DateTime.now().millisecondsSinceEpoch}_$name');
    await File(r.files.single.path!).copy(dest.path);
    final b = Book(dest.path, name);
    final l = await Store.load();
    l.insert(0, b);
    await Store.save(l);
    _open(b);
  }

  Future<void> _open(Book b) async {
    final l = await Store.load();
    l.removeWhere((e) => e.path == b.path);
    l.insert(0, b);
    await Store.save(l);
    if (!mounted) return;
    await Navigator.push(
        context, MaterialPageRoute(builder: (_) => ReaderPage(book: b)));
    _refresh();
  }

  Future<void> _delete(Book b) async {
    final l = await Store.load();
    l.removeWhere((e) => e.path == b.path);
    await Store.save(l);
    try {
      await File(b.path).delete();
    } catch (_) {}
    _refresh();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('আমার বইসমূহ')),
        floatingActionButton: FloatingActionButton.extended(
            onPressed: _pick,
            icon: const Icon(Icons.add),
            label: const Text('PDF খুলুন')),
        body: _books.isEmpty
            ? const Center(child: Text('কোনো বই নেই। নিচের বাটনে চেপে PDF যোগ করুন।'))
            : ListView.builder(
                itemCount: _books.length,
                itemBuilder: (_, i) {
                  final b = _books[i];
                  final prog =
                      b.total > 0 ? 'পেজ ${b.page} / ${b.total}' : 'পেজ ${b.page}';
                  return ListTile(
                    leading:
                        const Icon(Icons.picture_as_pdf, color: Colors.red),
                    title: Text(b.name, maxLines: 2, overflow: TextOverflow.ellipsis),
                    subtitle: Text(prog),
                    onTap: () => _open(b),
                    trailing: IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () => _delete(b)),
                  );
                },
              ),
      );
}

// ---------------- রিডার ----------------

class Place {
  String text = '';
  double size = 18, x = 0.1, y = 0.1, w = 0.4;
}

class ReaderPage extends StatefulWidget {
  final Book book;
  const ReaderPage({super.key, required this.book});
  @override
  State<ReaderPage> createState() => _ReaderPageState();
}

class _ReaderPageState extends State<ReaderPage> {
  Uint8List? _bytes;
  PdfViewerController _ctrl = PdfViewerController();
  GlobalKey<SfPdfViewerState> _key = GlobalKey<SfPdfViewerState>();
  int _page = 1, _total = 0;
  bool _dirty = false, _busy = false;
  Color _color = Colors.yellow;
  Annotation? _selected;

  @override
  void initState() {
    super.initState();
    _page = widget.book.page;
    File(widget.book.path).readAsBytes().then((b) {
      if (mounted) setState(() => _bytes = b);
    });
  }

  void _snack(String m) => ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text(m)));

  Future<void> _save() async {
    if (!_dirty) return;
    final out = await _ctrl.saveDocument();
    await File(widget.book.path).writeAsBytes(out);
    _dirty = false;
  }

  // --- হাইলাইট / আন্ডারলাইন / মার্কিং ---
  void _annotate(String type) {
    final lines = _key.currentState?.getSelectedTextLines();
    if (lines == null || lines.isEmpty) {
      _snack('আগে লেখার উপর চেপে ধরে টেক্সট সিলেক্ট করুন');
      return;
    }
    final Annotation a;
    if (type == 'h') {
      a = HighlightAnnotation(textBoundsCollection: lines);
    } else if (type == 'u') {
      a = UnderlineAnnotation(textBoundsCollection: lines);
    } else {
      a = StrikethroughAnnotation(textBoundsCollection: lines);
    }
    a.color = _color;
    _ctrl.addAnnotation(a);
    _dirty = true;
    _ctrl.clearSelection();
  }

  void _removeSelected() {
    if (_selected == null) return;
    _ctrl.removeAnnotation(_selected!);
    _dirty = true;
    setState(() => _selected = null);
  }

  // --- এডিটিং ---
  Future<void> _applyEdit(Future<void> Function(pdf.PdfDocument d) fn) async {
    setState(() => _busy = true);
    try {
      final current = Uint8List.fromList(await _ctrl.saveDocument());
      final d = pdf.PdfDocument(inputBytes: current);
      await fn(d);
      final out = Uint8List.fromList(await d.save());
      d.dispose();
      await File(widget.book.path).writeAsBytes(out);
      if (!mounted) return;
      setState(() {
        _bytes = out;
        _ctrl = PdfViewerController();
        _key = GlobalKey<SfPdfViewerState>();
        _selected = null;
        _dirty = false;
      });
    } catch (e) {
      _snack('ত্রুটি: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<(Uint8List, double, double)> _textToPng(
      String text, double size, Color color) async {
    const scale = 3.0;
    final tp = TextPainter(
      text: TextSpan(
          text: text, style: TextStyle(fontSize: size * scale, color: color)),
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: 1500);
    final rec = ui.PictureRecorder();
    tp.paint(Canvas(rec), Offset.zero);
    final img = await rec
        .endRecording()
        .toImage(tp.width.ceil() + 2, tp.height.ceil() + 2);
    final bd = await img.toByteData(format: ui.ImageByteFormat.png);
    return (bd!.buffer.asUint8List(), tp.width / scale, tp.height / scale);
  }

  Widget _slider(String l, double v, double min, double max,
          ValueChanged<double> f) =>
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(l),
        Slider(value: v, min: min, max: max, onChanged: f),
      ]);

  Future<Place?> _askPlace({required bool isText}) {
    final p = Place();
    final tc = TextEditingController();
    return showDialog<Place>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: Text(isText ? 'লেখা যোগ করুন' : 'ছবি/সই বসান'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              if (isText)
                TextField(
                    controller: tc,
                    maxLines: 3,
                    decoration: const InputDecoration(hintText: 'এখানে লিখুন')),
              if (isText)
                _slider('ফন্ট সাইজ: ${p.size.round()}', p.size, 8, 60,
                    (v) => set(() => p.size = v)),
              if (!isText)
                _slider('প্রস্থ', p.w, 0.1, 1, (v) => set(() => p.w = v)),
              _slider('ডানে সরান (X)', p.x, 0, 0.95, (v) => set(() => p.x = v)),
              _slider('নিচে নামান (Y)', p.y, 0, 0.95, (v) => set(() => p.y = v)),
            ]),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(c), child: const Text('বাতিল')),
            FilledButton(
                onPressed: () {
                  p.text = tc.text;
                  Navigator.pop(c, p);
                },
                child: const Text('যোগ করুন')),
          ],
        ),
      ),
    );
  }

  Future<void> _addText() async {
    final p = await _askPlace(isText: true);
    if (p == null || p.text.trim().isEmpty) return;
    final png = await _textToPng(p.text, p.size, Colors.black);
    await _applyEdit((d) async {
      final page = d.pages[_page - 1];
      final s = page.size;
      page.graphics.drawImage(pdf.PdfBitmap(png.$1),
          Rect.fromLTWH(s.width * p.x, s.height * p.y, png.$2, png.$3));
    });
  }

  Future<void> _addImage() async {
    final r = await FilePicker.platform.pickFiles(type: FileType.image);
    if (r == null || r.files.single.path == null) return;
    final bytes = await File(r.files.single.path!).readAsBytes();
    final p = await _askPlace(isText: false);
    if (p == null) return;
    await _applyEdit((d) async {
      final page = d.pages[_page - 1];
      final s = page.size;
      final bmp = pdf.PdfBitmap(bytes);
      final w = s.width * p.w;
      final h = w * bmp.height / bmp.width;
      page.graphics
          .drawImage(bmp, Rect.fromLTWH(s.width * p.x, s.height * p.y, w, h));
    });
  }

  Future<void> _rotate() => _applyEdit((d) async {
        final pg = d.pages[_page - 1];
        const v = pdf.PdfPageRotateAngle.values;
        pg.rotation = v[(pg.rotation.index + 1) % v.length];
      });

  Future<void> _deletePage() async {
    if (_total <= 1) {
      _snack('শেষ পেজ মোছা যাবে না');
      return;
    }
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text('পেজ $_page মুছবেন?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('না')),
          FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('হ্যাঁ')),
        ],
      ),
    );
    if (ok != true) return;
    await _applyEdit((d) async => d.pages.removeAt(_page - 1));
  }

  Future<void> _merge() async {
    final r = await FilePicker.platform
        .pickFiles(type: FileType.custom, allowedExtensions: ['pdf']);
    if (r == null || r.files.single.path == null) return;
    final other = await File(r.files.single.path!).readAsBytes();
    await _applyEdit((d) async {
      final src = pdf.PdfDocument(inputBytes: other);
      d.pageSettings.margins.all = 0;
      for (int i = 0; i < src.pages.count; i++) {
        final sp = src.pages[i];
        d.pageSettings.size = sp.size;
        d.pages.add().graphics.drawPdfTemplate(sp.createTemplate(), Offset.zero);
      }
      src.dispose();
    });
  }

  void _menu(String v) {
    switch (v) {
      case 'text':
        _addText();
        break;
      case 'image':
        _addImage();
        break;
      case 'rotate':
        _rotate();
        break;
      case 'delete':
        _deletePage();
        break;
      case 'merge':
        _merge();
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        await _save();
        if (mounted) Navigator.pop(context);
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.book.name,
              style: const TextStyle(fontSize: 16), overflow: TextOverflow.ellipsis),
          actions: [
            IconButton(
                icon: const Icon(Icons.save),
                tooltip: 'সেভ',
                onPressed: () async {
                  await _save();
                  _snack('সেভ হয়েছে');
                }),
            PopupMenuButton<String>(
              onSelected: _menu,
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'text', child: Text('লেখা যোগ করুন')),
                PopupMenuItem(value: 'image', child: Text('ছবি / সই যোগ করুন')),
                PopupMenuItem(value: 'rotate', child: Text('পেজ ঘোরান')),
                PopupMenuItem(value: 'delete', child: Text('এই পেজ মুছুন')),
                PopupMenuItem(value: 'merge', child: Text('আরেকটি PDF জুড়ুন')),
              ],
            ),
          ],
        ),
        body: _bytes == null
            ? const Center(child: CircularProgressIndicator())
            : Stack(children: [
                SfPdfViewer.memory(
                  _bytes!,
                  key: _key,
                  controller: _ctrl,
                  onDocumentLoaded: (d) {
                    _total = d.document.pages.count;
                    if (_page > _total) _page = _total;
                    if (_page > 1) _ctrl.jumpToPage(_page);
                    Store.updatePage(widget.book.path, _page, _total);
                    setState(() {});
                  },
                  onPageChanged: (d) {
                    _page = d.newPageNumber;
                    Store.updatePage(widget.book.path, _page, _total);
                    setState(() {});
                  },
                  onAnnotationSelected: (a) => setState(() => _selected = a),
                  onAnnotationDeselected: (a) =>
                      setState(() => _selected = null),
                ),
                if (_busy)
                  const Center(child: CircularProgressIndicator()),
              ]),
        bottomNavigationBar: BottomAppBar(
          height: 64,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              IconButton(
                  icon: const Icon(Icons.border_color),
                  tooltip: 'হাইলাইট',
                  onPressed: () => _annotate('h')),
              IconButton(
                  icon: const Icon(Icons.format_underlined),
                  tooltip: 'আন্ডারলাইন',
                  onPressed: () => _annotate('u')),
              IconButton(
                  icon: const Icon(Icons.format_strikethrough),
                  tooltip: 'কাটা দাগ',
                  onPressed: () => _annotate('s')),
              PopupMenuButton<Color>(
                tooltip: 'রঙ',
                icon: Icon(Icons.circle, color: _color),
                onSelected: (c) => setState(() => _color = c),
                itemBuilder: (_) => [
                  for (final c in [
                    Colors.yellow,
                    Colors.green,
                    Colors.pinkAccent,
                    Colors.lightBlue,
                    Colors.orange,
                    Colors.red,
                  ])
                    PopupMenuItem(
                        value: c, child: Icon(Icons.circle, color: c)),
                ],
              ),
              IconButton(
                  icon: const Icon(Icons.delete_sweep),
                  tooltip: 'সিলেক্ট করা মার্ক মুছুন',
                  onPressed: _selected == null ? null : _removeSelected),
              Text('$_page/$_total'),
            ],
          ),
        ),
      ),
    );
  }
}
