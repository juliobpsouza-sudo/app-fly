param([Parameter(Mandatory = $true)][string]$Path)
# Copia para a área de transferência do Windows: o arquivo (cola no Explorer/WhatsApp/Discord)
# e, se for foto, também a imagem já na orientação certa (cola no Paint/Word/etc.).
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$d = New-Object System.Windows.Forms.DataObject
$sc = New-Object System.Collections.Specialized.StringCollection
[void]$sc.Add($Path)
$d.SetFileDropList($sc)
try {
    $img = [System.Drawing.Image]::FromFile($Path)
    if ($img.PropertyIdList -contains 274) {                      # 274 = orientação EXIF da câmera
        $o = [int]$img.GetPropertyItem(274).Value[0]
        $names = @{ 2 = 'RotateNoneFlipX'; 3 = 'Rotate180FlipNone'; 4 = 'RotateNoneFlipY'; 5 = 'Rotate90FlipX'; 6 = 'Rotate90FlipNone'; 7 = 'Rotate270FlipX'; 8 = 'Rotate270FlipNone' }
        if ($names.ContainsKey($o)) { $img.RotateFlip([System.Drawing.RotateFlipType]::($names[$o])) }
    }
    $bmp = New-Object System.Drawing.Bitmap($img)                 # cópia na memória, solta o arquivo
    $d.SetImage($bmp)
    $img.Dispose()
} catch { }                                                       # não é imagem que o Windows leia: fica só o arquivo
[System.Windows.Forms.Clipboard]::SetDataObject($d, $true)
