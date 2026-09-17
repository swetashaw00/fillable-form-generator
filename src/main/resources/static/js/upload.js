const dropzone = document.getElementById('dropzone');
const fileInput = document.getElementById('fileInput');
const statusEl = document.getElementById('status');

dropzone.addEventListener('click', () => fileInput.click());

dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('dragover');
});

dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));

dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
    if (e.dataTransfer.files.length > 0) {
        uploadFile(e.dataTransfer.files[0]);
    }
});

fileInput.addEventListener('change', () => {
    if (fileInput.files.length > 0) {
        uploadFile(fileInput.files[0]);
    }
});

function setStatus(message, isError) {
    statusEl.textContent = message;
    statusEl.className = isError ? 'status error' : 'status';
}

async function uploadFile(file) {
    const name = file.name.toLowerCase();
    if (!name.endsWith('.pdf') && !name.endsWith('.docx')) {
        setStatus('Only .pdf and .docx files are supported.', true);
        return;
    }

    setStatus('Uploading and analyzing document...', false);

    const formData = new FormData();
    formData.append('file', file);

    try {
        const response = await fetch('/api/documents', { method: 'POST', body: formData });
        const body = await response.json();
        if (!response.ok) {
            throw new Error(body.error || 'Upload failed');
        }
        setStatus('Opening editor...', false);
        window.location.href = `editor.html?id=${encodeURIComponent(body.id)}`;
    } catch (err) {
        setStatus(err.message, true);
    }
}
