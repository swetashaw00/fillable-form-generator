pdfjsLib.GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';

const SCALE = 1.3;
const MIN_BOX_PX = 16;

const documentId = new URLSearchParams(window.location.search).get('id');
const viewport = document.getElementById('pdfViewport');
const fieldForm = document.getElementById('fieldForm');
const docNameEl = document.getElementById('docName');
const generateBtn = document.getElementById('generateBtn');
const generateStatus = document.getElementById('generateStatus');
const downloadPdfBtn = document.getElementById('downloadPdfBtn');
const downloadDocxBtn = document.getElementById('downloadDocxBtn');
const placingHint = document.getElementById('placingHint');
const autofillBtn = document.getElementById('autofillBtn');
const autofillText = document.getElementById('autofillText');
const autofillStatus = document.getElementById('autofillStatus');

let fields = [];
let selectedFieldId = null;
let placingType = null;
let pageHeightsPts = [];
const boxElements = new Map();

if (!documentId) {
    viewport.innerHTML = '<p class="empty-hint">No document id in URL. Upload a document first.</p>';
} else {
    init();
}

async function init() {
    const meta = await fetchJson(`/api/documents/${documentId}`);
    fields = meta.fields || [];
    docNameEl.textContent = meta.originalFilename;

    const pdfResponse = await fetch(`/api/documents/${documentId}/pdf`);
    const pdfData = await pdfResponse.arrayBuffer();
    const pdf = await pdfjsLib.getDocument({ data: pdfData }).promise;

    viewport.innerHTML = '';
    for (let pageNum = 1; pageNum <= pdf.numPages; pageNum++) {
        const page = await pdf.getPage(pageNum);
        const unscaledViewport = page.getViewport({ scale: 1 });
        pageHeightsPts[pageNum - 1] = unscaledViewport.height;

        const renderViewport = page.getViewport({ scale: SCALE });
        const canvas = document.createElement('canvas');
        canvas.width = renderViewport.width;
        canvas.height = renderViewport.height;

        const pageWrap = document.createElement('div');
        pageWrap.className = 'page-wrap';
        pageWrap.style.width = renderViewport.width + 'px';
        pageWrap.style.height = renderViewport.height + 'px';
        pageWrap.dataset.page = String(pageNum - 1);
        pageWrap.appendChild(canvas);
        viewport.appendChild(pageWrap);

        await page.render({ canvasContext: canvas.getContext('2d'), viewport: renderViewport }).promise;

        wirePagePlacement(pageWrap);
    }

    fields.forEach(renderFieldBox);
    updateSidePanel();
}

function fetchJson(url, options) {
    return fetch(url, options).then(async (res) => {
        const body = await res.json();
        if (!res.ok) throw new Error(body.error || 'Request failed');
        return body;
    });
}

// ---- coordinate conversion (PDF points, bottom-left origin) <-> (canvas px, top-left origin) ----

function toCanvasBox(field) {
    const pageHeightPts = pageHeightsPts[field.page];
    return {
        left: field.x * SCALE,
        top: (pageHeightPts - field.y - field.height) * SCALE,
        width: field.width * SCALE,
        height: field.height * SCALE
    };
}

function applyCanvasBoxToField(field, box) {
    const pageHeightPts = pageHeightsPts[field.page];
    field.x = box.left / SCALE;
    field.width = box.width / SCALE;
    field.height = box.height / SCALE;
    field.y = pageHeightPts - (box.top / SCALE) - field.height;
}

// ---- rendering ----

function renderFieldBox(field) {
    const pageWrap = viewport.querySelector(`.page-wrap[data-page="${field.page}"]`);
    if (!pageWrap) return;

    const el = document.createElement('div');
    el.className = 'field-box' + (field.type === 'CHECKBOX' ? ' checkbox' : '') + (field.value ? ' filled' : '');

    const label = document.createElement('span');
    label.className = 'field-label';
    label.textContent = field.name;
    el.appendChild(label);

    const handle = document.createElement('div');
    handle.className = 'resize-handle';
    el.appendChild(handle);

    applyBoxStyle(el, toCanvasBox(field));
    pageWrap.appendChild(el);
    boxElements.set(field.id, el);

    wireBoxInteractions(el, field, handle);
}

function applyBoxStyle(el, box) {
    el.style.left = box.left + 'px';
    el.style.top = box.top + 'px';
    el.style.width = box.width + 'px';
    el.style.height = box.height + 'px';
}

function refreshFieldBox(field) {
    const el = boxElements.get(field.id);
    if (el) {
        el.querySelector('.field-label').textContent = field.name;
        el.className = 'field-box' + (field.type === 'CHECKBOX' ? ' checkbox' : '') +
            (field.value ? ' filled' : '') +
            (field.id === selectedFieldId ? ' selected' : '');
        applyBoxStyle(el, toCanvasBox(field));
    }
}

function removeFieldBox(field) {
    const el = boxElements.get(field.id);
    if (el) el.remove();
    boxElements.delete(field.id);
}

// ---- selection + side panel ----

function selectField(id) {
    selectedFieldId = id;
    boxElements.forEach((el, fieldId) => {
        el.classList.toggle('selected', fieldId === id);
    });
    updateSidePanel();
}

function updateSidePanel() {
    const field = fields.find((f) => f.id === selectedFieldId);
    if (!field) {
        fieldForm.innerHTML = '<p class="empty-hint">Select a field to edit it, or add a new one from the left toolbar.</p>';
        return;
    }

    fieldForm.innerHTML = `
        <label>Field name
            <input type="text" id="fName" value="${escapeHtml(field.name)}">
        </label>
        <label>Type
            <select id="fType">
                <option value="TEXT">Text</option>
                <option value="DATE">Date</option>
                <option value="CHECKBOX">Checkbox</option>
                <option value="SIGNATURE">Signature</option>
            </select>
        </label>
        <label>Value
            <input type="text" id="fValue" value="${escapeHtml(field.value || '')}"
                placeholder="${field.type === 'CHECKBOX' ? 'yes / no' : 'Filled from pasted text, or type here'}">
        </label>
        <div class="checkbox-row">
            <input type="checkbox" id="fRequired">
            <label for="fRequired" style="margin:0;">Required</label>
        </div>
        <button class="danger-btn" id="fDelete">Delete field</button>
    `;
    document.getElementById('fType').value = field.type;
    document.getElementById('fRequired').checked = field.required;

    document.getElementById('fName').addEventListener('input', (e) => {
        field.name = e.target.value;
        refreshFieldBox(field);
    });
    document.getElementById('fValue').addEventListener('input', (e) => {
        field.value = e.target.value;
        refreshFieldBox(field);
    });
    document.getElementById('fType').addEventListener('change', (e) => {
        field.type = e.target.value;
        refreshFieldBox(field);
    });
    document.getElementById('fRequired').addEventListener('change', (e) => {
        field.required = e.target.checked;
    });
    document.getElementById('fDelete').addEventListener('click', () => {
        fields = fields.filter((f) => f.id !== field.id);
        removeFieldBox(field);
        selectedFieldId = null;
        updateSidePanel();
    });
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

// ---- drag + resize for existing boxes ----

function wireBoxInteractions(el, field, handle) {
    el.addEventListener('mousedown', (e) => {
        if (e.target === handle) return;
        e.stopPropagation();
        selectField(field.id);

        const pageWrap = el.parentElement;
        const startMouseX = e.clientX;
        const startMouseY = e.clientY;
        const startLeft = el.offsetLeft;
        const startTop = el.offsetTop;

        function onMove(moveEvt) {
            const dx = moveEvt.clientX - startMouseX;
            const dy = moveEvt.clientY - startMouseY;
            const newLeft = clamp(startLeft + dx, 0, pageWrap.clientWidth - el.offsetWidth);
            const newTop = clamp(startTop + dy, 0, pageWrap.clientHeight - el.offsetHeight);
            el.style.left = newLeft + 'px';
            el.style.top = newTop + 'px';
        }

        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            applyCanvasBoxToField(field, {
                left: el.offsetLeft, top: el.offsetTop,
                width: el.offsetWidth, height: el.offsetHeight
            });
        }

        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    });

    handle.addEventListener('mousedown', (e) => {
        e.stopPropagation();
        selectField(field.id);

        const pageWrap = el.parentElement;
        const startMouseX = e.clientX;
        const startMouseY = e.clientY;
        const startWidth = el.offsetWidth;
        const startHeight = el.offsetHeight;

        function onMove(moveEvt) {
            const dx = moveEvt.clientX - startMouseX;
            const dy = moveEvt.clientY - startMouseY;
            const newWidth = clamp(startWidth + dx, MIN_BOX_PX, pageWrap.clientWidth - el.offsetLeft);
            const newHeight = clamp(startHeight + dy, MIN_BOX_PX, pageWrap.clientHeight - el.offsetTop);
            el.style.width = newWidth + 'px';
            el.style.height = newHeight + 'px';
        }

        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            applyCanvasBoxToField(field, {
                left: el.offsetLeft, top: el.offsetTop,
                width: el.offsetWidth, height: el.offsetHeight
            });
        }

        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    });
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

// crypto.randomUUID() only exists in secure contexts (https, or http://localhost) --
// browsing the editor via a LAN IP (e.g. http://192.168.x.x:8080) is insecure, so
// that call throws and silently aborts field creation. getRandomValues() has no such
// restriction, so build a UUID v4 from it instead, with a non-crypto fallback for
// contexts missing both.
function generateId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    if (window.crypto && typeof window.crypto.getRandomValues === 'function') {
        const bytes = new Uint8Array(16);
        crypto.getRandomValues(bytes);
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'));
        return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-` +
            `${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`;
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

// ---- placing new fields ----

document.querySelectorAll('.tool-btn[data-type]').forEach((btn) => {
    btn.addEventListener('click', () => {
        const alreadyActive = btn.classList.contains('active');
        document.querySelectorAll('.tool-btn[data-type]').forEach((b) => b.classList.remove('active'));
        if (alreadyActive) {
            placingType = null;
            placingHint.style.display = 'none';
        } else {
            btn.classList.add('active');
            placingType = btn.dataset.type;
            placingHint.style.display = 'block';
        }
    });
});

function wirePagePlacement(pageWrap) {
    pageWrap.addEventListener('mousedown', (e) => {
        if (!placingType) {
            selectField(null);
            return;
        }
        const rect = pageWrap.getBoundingClientRect();
        const startX = e.clientX - rect.left;
        const startY = e.clientY - rect.top;

        const ghost = document.createElement('div');
        ghost.className = 'field-box';
        applyBoxStyle(ghost, { left: startX, top: startY, width: 1, height: 1 });
        pageWrap.appendChild(ghost);

        function onMove(moveEvt) {
            const curX = moveEvt.clientX - rect.left;
            const curY = moveEvt.clientY - rect.top;
            applyBoxStyle(ghost, {
                left: Math.min(startX, curX),
                top: Math.min(startY, curY),
                width: Math.abs(curX - startX),
                height: Math.abs(curY - startY)
            });
        }

        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            ghost.remove();

            const box = {
                left: parseFloat(ghost.style.left),
                top: parseFloat(ghost.style.top),
                width: Math.max(parseFloat(ghost.style.width), MIN_BOX_PX),
                height: Math.max(parseFloat(ghost.style.height), MIN_BOX_PX)
            };

            const field = {
                id: generateId(),
                page: Number(pageWrap.dataset.page),
                type: placingType,
                name: `${placingType.toLowerCase()}_${fields.length + 1}`,
                required: false,
                autoDetected: false,
                x: 0, y: 0, width: 0, height: 0
            };
            applyCanvasBoxToField(field, box);
            fields.push(field);
            renderFieldBox(field);
            selectField(field.id);

            placingType = null;
            placingHint.style.display = 'none';
            document.querySelectorAll('.tool-btn[data-type]').forEach((b) => b.classList.remove('active'));
        }

        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    });
}

function triggerDownload(url) {
    const link = document.createElement('a');
    link.href = url;
    link.download = '';
    document.body.appendChild(link);
    link.click();
    link.remove();
}

// ---- save fields ----

function saveFields() {
    return fetchJson(`/api/documents/${documentId}/fields`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(fields)
    });
}

// ---- autofill from pasted text ----

autofillBtn.addEventListener('click', async () => {
    const text = autofillText.value.trim();
    if (!text) {
        autofillStatus.textContent = 'Paste some text first.';
        autofillStatus.className = 'status error';
        return;
    }
    autofillBtn.disabled = true;
    autofillStatus.textContent = 'Matching fields...';
    autofillStatus.className = 'status';
    try {
        await saveFields();
        const result = await fetchJson(`/api/documents/${documentId}/autofill`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ text })
        });
        fields = result.fields;
        fields.forEach(refreshFieldBox);
        if (selectedFieldId) {
            updateSidePanel();
        }
        const filledCount = fields.filter((f) => f.value).length;
        autofillStatus.textContent = `Filled ${filledCount} of ${fields.length} field(s). Review values on the right, then generate.`;
    } catch (err) {
        autofillStatus.textContent = err.message;
        autofillStatus.className = 'status error';
    } finally {
        autofillBtn.disabled = false;
    }
});

// ---- generate ----

generateBtn.addEventListener('click', async () => {
    generateBtn.disabled = true;
    downloadPdfBtn.style.display = 'none';
    downloadDocxBtn.style.display = 'none';
    generateStatus.textContent = 'Saving fields...';
    generateStatus.className = 'status';
    try {
        await saveFields();
        generateStatus.textContent = 'Generating fillable form(s)...';
        const result = await fetchJson(`/api/documents/${documentId}/generate`, { method: 'POST' });

        downloadPdfBtn.onclick = () => triggerDownload(result.pdfDownloadUrl);
        downloadPdfBtn.style.display = 'block';
        let message = 'Ready.';

        if (result.docxDownloadUrl) {
            downloadDocxBtn.onclick = () => triggerDownload(result.docxDownloadUrl);
            downloadDocxBtn.style.display = 'block';
            if (result.docxSkippedFields && result.docxSkippedFields.length > 0) {
                message += ` (${result.docxSkippedFields.length} field(s) couldn't be placed in the ` +
                    `docx -- manually-added fields have no anchor in a flowing Word document: ` +
                    `${result.docxSkippedFields.join(', ')})`;
            }
        }

        generateStatus.textContent = message;
    } catch (err) {
        generateStatus.textContent = err.message;
        generateStatus.className = 'status error';
    } finally {
        generateBtn.disabled = false;
    }
});
